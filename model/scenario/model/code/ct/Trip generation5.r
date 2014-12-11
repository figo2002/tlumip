# Run simple trip generation for CT synthetic firms at the alpha zone level
library(data.table)
library(dplyr)
library(stringr)
library(reshape2)

generate_local_truck_origins <- function() {
    # Introduce yourself and set the random seed
    set.seed(as.integer(RTP[["ct.random.seed"]]))
    print(str_c("------- generate_local_truck_origins -------"), quote=FALSE)
    
    # When we read the synthetic firm data we will aggregate sector to industry,
    # where the latter is first token in the former (i.e., characters up to but
    # not including the first underscore). We will use these categories to trip
    # and tour generation.
    load(str_c(RTP[["Working_Folder"]], "ct-alpha-synthetic-firms.RData"))
    # Pull the prefix from the sector and store it as Industry.
    x <- str_locate(firms$Sector, '_')[,1]  # Remember: str_locate returns array
    firms$Industry <- str_sub(firms$Sector, 1, x-1)
    
    # Read the trip generation probabilities and convert from wide to tall
    # format, dropping cases where the probability is zero.
    FN <- str_c(RTP[["ct.properties.folder"]], "/ct-local-generation-probabilities.csv")
    raw <- fread(FN)
    probabilities <- melt(raw, id.vars="Industry", variable.name="truck_type",
        value.name="p_gen") %>% filter(p_gen>0.0)
    truck_types <- unique(probabilities$truck_type)
    
    # Start by showing the aggregate trip (AT) generation using these values,
    # which we'll compare the sum of microsimulated values to in calibration.
    AT <- firms %>% group_by(Industry) %>% summarise(Employees = sum(Employees))
    aggregate_results <- data.table()
    for (t in truck_types) {
        p <- filter(probabilities, truck_type==t)
        p <- merge(p, AT, by="Industry") %>%
            mutate(tripends = round(Employees*p_gen, 1)) %>%
            select(-p_gen, -Employees)
        aggregate_results <- rbind(aggregate_results, p)
    }
        
    # Finally, let's do the generation. We'll step through this process for each
    # truck type defined in the trip generation probabilities.
    simulation.start <- proc.time()
    results <- data.table()   # Container to hold the generated trips
    for (t in truck_types) {
        # Create a subset of the probabilities for this truck type, which we'll 
        # merge with the alpha zone data
        p <- filter(probabilities, truck_type==t)
        # Not all industries generate all truck types, so let's pull a subset of
        # the firms that will do so.
        industries <- unique(as.character(p$Industry))
        f <- filter(firms, Industry %in% industries) %>% select(-Sector, -fips)
        p <- merge(p, f, by="Industry", all.y=TRUE)  # Include all firms
        
        # Calculate the total daily trips generated by each firm and sum them
        # for a target total. We will require the sum of the microsimulated
        # trips to not deviate more than a user-specified difference from this
        # initial total. So we will loop until we fall within that window.
        success <- FALSE
        stage <- ""
        trials <- 1
        while (!success) {
            # Check to make sure that we haven't exceeded the maximum number of 
            # attempts at replanning. If we have something pathological has
            # happened and we need to stop dead in our tracks.
            if (trials>as.integer(RTP[["ct.maximum.resampling.attempts"]])) {
                stop(str_c("Max resampling exceeded while working on ", t,
                    " generation"))
            }
            
            # Calculate our target
            p$daily_trips <- p$Employees*p$p_gen
            initial_total <- round(sum(p$daily_trips), 1)
            
            # Generate discrete number of trucks for each firm. Use random draws
            # to decide what to do with the fractional part of the trips.
            pw <- trunc(p$daily_trips)   # Retain the whole part
            fp <- p$daily_trips-pw    # Retain the fractional part
            rn <- runif(length(fp), 0, 1)   # Random draws to compare to
            p$daily_trips <- ifelse(rn<=fp, pw+1, pw)
            adjusted_total <- sum(p$daily_trips)
            pct_difference<-((adjusted_total-initial_total)/initial_total)*100.0
            
            # Show us how we did
            print(paste(stage, t, " origins: combined=", initial_total,
                " simulated=", adjusted_total, " difference=",
                round(pct_difference,2), "%", sep=''), quote=FALSE)
            
            # Evaluate success
            success <- abs(pct_difference) <= as.integer(RTP[["ct.resampling.threshold"]])
            stage <- "Resampling "
            trials <- trials+1
        }
        
        # For now we'll combine the results for this truck type with all of the
        # others processed. At this point we're appended the number of daily
        # truck trips by truck type to each record of the firm data.
        p <- filter(p, daily_trips>0)
        results <- rbind(results, p)
    }
    
    # Let's create a list that repeats the firmID by the number of daily trips.
    # Because each firm can generate more than one type of truck we have one or
    # more trips from the same firm. We'll need to use a merge key that handles
    # both firmID and truck type.
    origins <- rep(results$firmID, results$daily_trips)
    trucks <- rep(results$truck_type, results$daily_trips)
    trip_records <- data.table(firmID = as.integer(origins), 
        truck_type = as.character(trucks))
    
    # Now we simply merge this with the input data table, ensuring that all of
    # the origins get matched with the firm's information. We'll drop the info
    # that we don't need in subsequent models.
    truck_origins <- merge(trip_records, results, by=c("firmID", "truck_type"),
        all.x=TRUE) %>% select(-p_gen, -daily_trips)
    
    # Write the resulting trip list and exit stage right
    simulation.stop <- proc.time()
    elapsed_seconds <- round((simulation.stop-simulation.start)[["elapsed"]], 1)
    print(str_c("Simulation time=", elapsed_seconds, " seconds"), quote=FALSE)
    FN <- str_c(RTP[["Working_Folder"]], "ct-internal-truck-origins.RData")
    save(truck_origins, file=FN)
    print(str_c(nrow(truck_origins), " truck origins saved in ", FN), quote=FALSE)
    if (RTP[["ct.extended.trace"]]=="TRUE") {
        FN <- str_c(RTP[["Working_Folder"]], "ct-internal-truck-origins.csv")
        write.table(truck_origins, file=FN, sep=',', quote=FALSE, row.names=FALSE)
    }
    
    # TO-DO: Compare the initial aggregate results with the microsimulated ones,
    # summed by industry--but only if we keep this procedure in use
    print("", quote=FALSE) # Put whitespace between this report and the next one

}

TG <- generate_local_truck_origins()