/*
 * Copyright  2005 PB Consult Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.pb.tlumip.ts;

/**
 *
 * @author    Jim Hicks
 * @version   1.0, 6/30/2004
 */


import com.pb.tlumip.model.ModeType;


import com.pb.common.datafile.OLD_CSVFileReader;
import com.pb.common.datafile.TableDataSet;

import com.pb.common.util.ResourceUtil;

import java.util.HashMap;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.ResourceBundle;

import org.apache.log4j.Logger;



public class DemandHandler implements DemandHandlerIF {

	protected static Logger logger = Logger.getLogger(DemandHandler.class);

    
    final char[] highwayModeCharacters = { 'a', 'd', 'e', 'f', 'g', 'h' };

    String componentPropertyName;
    String globalPropertyName;
    
	HashMap componentPropertyMap;
    HashMap globalPropertyMap;

    int networkNumCentroids;
    int networkNumUserClasses;
    int[] networkNodeIndexArray;
    boolean networkUserClassesIncludeTruck;
    HashMap networkAssignmentGroupMap;

    
	double[][][] multiclassTripTable = null;
    
    double[][] multiclassTripTableRowSums = null;

	


    public DemandHandler() {
    }

    
    // Factory Method to return either local or remote instance
    public static DemandHandlerIF getInstance( String rpcConfigFile ) {
    
        // if false, remote method calls on networkHandler are made 
        boolean localFlag = true;
    
        //This method needs to be written
        //if (DafNode.getInstance().isHandlerLocal("demandHandler"))
        //    localFlag = true;
    
        if (localFlag == false && rpcConfigFile != null) {
            return new DemandHandlerRpc( rpcConfigFile );
        }
        else { 
            return new DemandHandler();
        }
        
    }
    

    // Factory Method to return local instance only
    public static DemandHandlerIF getInstance() {
        return new DemandHandler();
    }
    

   

    
    public boolean setup( HashMap componentPropertyMap, HashMap globalPropertyMap, String timePeriod ) {
        
        this.componentPropertyMap = componentPropertyMap;
        this.globalPropertyMap = globalPropertyMap; 
        
        return buildDemandObject( timePeriod );
        
    }
    
    
    public boolean setup( ResourceBundle componentRb, ResourceBundle globalRb, String timePeriod ) {
        

        this.componentPropertyMap = ResourceUtil.changeResourceBundleIntoHashMap( componentRb );
        this.globalPropertyMap = ResourceUtil.changeResourceBundleIntoHashMap( globalRb );

        return buildDemandObject( timePeriod );
        
    }
    
    
    public int setNetworkAttributes( int numCentroids, int numUserClasses, int[] nodeIndexArray, HashMap assignmentGroupMap, boolean userClassesIncludeTruck ) {
        
        networkNumCentroids = numCentroids;
        networkNumUserClasses = numUserClasses;
        networkAssignmentGroupMap = assignmentGroupMap;
        networkNodeIndexArray = nodeIndexArray;
        networkUserClassesIncludeTruck = userClassesIncludeTruck;
        networkAssignmentGroupMap = assignmentGroupMap;
        
        return 1;
    }
    
    
    public double[] getTripTableRow ( int userClass, int row ) {
        return multiclassTripTable[userClass][row];
    }
    
    
    public double[][][] getMulticlassTripTables () {
        return multiclassTripTable;
    }
    
    
    public double[][] getTripTableRowSums () {
        return multiclassTripTableRowSums;
    }
    
    

    public double[][] getWalkTransitTripTable ( String timePeriod ) {
        
        int startHour = 0;
        int endHour = 0;

        // get trip list filenames from property file
        String ptFileName = (String)componentPropertyMap.get("pt.fileName");

        if ( timePeriod.equalsIgnoreCase( "peak" ) ) {
            // get peak period definitions from property files
            startHour = Integer.parseInt( (String)globalPropertyMap.get("AM_PEAK_START") );
            endHour = Integer.parseInt( (String)globalPropertyMap.get("AM_PEAK_END") );
        }
        else if ( timePeriod.equalsIgnoreCase( "offpeak" ) ) {
            // get off-peak period definitions from property files
            startHour = Integer.parseInt( (String)globalPropertyMap.get("OFF_PEAK_START") );
            endHour = Integer.parseInt( (String)globalPropertyMap.get("OFF_PEAK_END") );
        }

        return getWalkTransitTripTableFromPTList ( networkNodeIndexArray, ptFileName, startHour, endHour );
        
    }

    
    
    public double[][] getDriveTransitTripTable ( String timePeriod ) {
        
        int startHour = 0;
        int endHour = 0;

        // get trip list filenames from property file
        String ptFileName = (String)componentPropertyMap.get("pt.fileName");

        if ( timePeriod.equalsIgnoreCase( "peak" ) ) {
            // get peak period definitions from property files
            startHour = Integer.parseInt( (String)globalPropertyMap.get("AM_PEAK_START") );
            endHour = Integer.parseInt( (String)globalPropertyMap.get("AM_PEAK_END") );
        }
        else if ( timePeriod.equalsIgnoreCase( "offpeak" ) ) {
            // get off-peak period definitions from property files
            startHour = Integer.parseInt( (String)globalPropertyMap.get("OFF_PEAK_START") );
            endHour = Integer.parseInt( (String)globalPropertyMap.get("OFF_PEAK_END") );
        }

        return getDriveTransitTripTableFromPTList ( networkNodeIndexArray, ptFileName, startHour, endHour );
        
    }

    
    
    private boolean buildDemandObject( String timePeriod ) {
    	
        int i=0;
        int j=0;
        int k=0;
        
    	// load the trips from PT and CT trip lists into multiclass o/d demand matrices for assignment
        try {

            // read in the trip lists
            multiclassTripTable = createMulticlassDemandMatrices ( timePeriod );
            
            
            multiclassTripTableRowSums = new double[multiclassTripTable.length][multiclassTripTable[0].length];
            
            // summarize the trip table rows for each user class
            for (i=0; i < multiclassTripTable.length; i++)
                for (j=0; j < multiclassTripTable[i].length; j++)
                    for (k=0; k < multiclassTripTable[i][j].length; k++)
                        multiclassTripTableRowSums[i][j] += multiclassTripTable[i][j][k];
            
            
            return true;
            
        }
        catch (Exception e) {
            
            logger.error ("error building multiclass od demand matrices for " + timePeriod + " period.");
            logger.error ("multiclassTripTable.length=" + multiclassTripTable.length);
            logger.error ("multiclassTripTable[0].length=" + multiclassTripTable[0].length);
            logger.error ("multiclassTripTable[0][0].length=" + multiclassTripTable[0][0].length);
            logger.error ("multiclassTripTableRowSums.length=" + multiclassTripTableRowSums.length);
            logger.error ("multiclassTripTableRowSums[0].length=" + multiclassTripTableRowSums[0].length);
            logger.error ("i=" + i + ", j=" + j + ", k=" + k, e);
            return false;
            
        }
		
    }
    
    
    
    private double[][][] createMulticlassDemandMatrices ( String timePeriod ) {
        
		String myDateString;
		
    	double[][][] multiclassTripTable = new double[networkNumUserClasses][][];
		

    	int startHour = 0;
    	int endHour = 0;

        // get trip list filenames from property file
        String ptFileName = (String)componentPropertyMap.get("pt.fileName");
        String ctFileName = (String)componentPropertyMap.get("ct.fileName");

        

        if ( timePeriod.equalsIgnoreCase( "peak" ) ) {
            // get peak period definitions from property files
            startHour = Integer.parseInt( (String)globalPropertyMap.get("AM_PEAK_START") );
            endHour = Integer.parseInt( (String)globalPropertyMap.get("AM_PEAK_END") );
        }
        else if ( timePeriod.equalsIgnoreCase( "offpeak" ) ) {
            // get off-peak period definitions from property files
            startHour = Integer.parseInt( (String)globalPropertyMap.get("OFF_PEAK_START") );
            endHour = Integer.parseInt( (String)globalPropertyMap.get("OFF_PEAK_END") );
        }


		// check that at least one valid user class has been defined
		if ( networkNumUserClasses == 0 ) {
			logger.error ( "No valid user classes defined in " + componentPropertyName + " file.", new RuntimeException() );
		}
		
		HashMap assignmentGroupMap = networkAssignmentGroupMap;
		
        
		// read PT trip list into o/d trip matrix if auto user class was defined
		if ( assignmentGroupMap.containsKey( String.valueOf('a') ) ) {
			myDateString = DateFormat.getDateTimeInstance().format(new Date());
			logger.info ("reading " + timePeriod + " PT trip list at: " + myDateString);
			multiclassTripTable[0] = getAutoTripTableFromPTList ( networkNodeIndexArray, ptFileName, startHour, endHour );
		}
		else {
			logger.info ("no auto class defined, so " + timePeriod + " PT trip list was not read." );
		}

        
		// read CT trip list into o/d trip matrix if at least one truck class was defined
		if ( networkUserClassesIncludeTruck ) {
			myDateString = DateFormat.getDateTimeInstance().format(new Date());
			logger.info ("reading " + timePeriod + " CT trip list at: " + myDateString);
			double[][][] truckTripTables = getTruckAssignmentGroupTripTableFromCTList ( assignmentGroupMap, networkNodeIndexArray, ctFileName, startHour, endHour );

			for(int i=0; i < truckTripTables.length - 1; i++)
				multiclassTripTable[i+1] = truckTripTables[i];
		}


        return multiclassTripTable;
		
    }
	
	

    
    private double[][] getAutoTripTableFromPTList ( int[] nodeIndex, String fileName, int startPeriod, int endPeriod ) {
        
        int orig;
        int dest;
        int startTime;
        int mode;
        int o;
        int d;
        int allAutoTripCount=0;
        int tripCount=0;
        
        
        double[][] tripTable = new double[networkNumCentroids+1][networkNumCentroids+1];
        

        
        // read the PT output person trip list file into a TableDataSet
        OLD_CSVFileReader reader = new OLD_CSVFileReader();

        String[] columnsToRead = { "origin", "destination", "tripStartTime", "tripMode" };
        TableDataSet table = null;
        try {
            if ( fileName != null) {

                table = reader.readFile(new File( fileName ), columnsToRead);

                // traverse the trip list in the TableDataSet and aggregate trips to an o/d trip table
                for (int i=0; i < table.getRowCount(); i++) {
                    
                    orig = (int)table.getValueAt( i+1, "origin" );
                    dest = (int)table.getValueAt( i+1, "destination" );
                    startTime = (int)table.getValueAt( i+1, "tripStartTime" );
                    mode = (int)table.getValueAt( i+1, "tripMode" );
                    
                    o = nodeIndex[orig];
                    d = nodeIndex[dest];
                    
                    // accumulate all peak period highway mode trips
                    if ( (mode == ModeType.AUTODRIVER || mode == ModeType.AUTOPASSENGER) ) {
                        
                        if ( (startTime >= startPeriod && startTime <= endPeriod) ) {
    
                            tripTable[o][d]++;
                            tripCount++;
                        
                        }

                        allAutoTripCount++;
                    }
                    
                }
                
                // done with trip list TabelDataSet
                table = null;

            }
            
        } catch (IOException e) {
            logger.error ( "", e );
        }


        logger.info (allAutoTripCount + " total auto network trips read from PT entire file");
        logger.info (tripCount + " total auto network trips read from PT file for period " + startPeriod +
                " to " + endPeriod);

        return tripTable;
            
    }
    

    private double[][] getWalkTransitTripTableFromPTList ( int[] nodeIndex, String fileName, int startPeriod, int endPeriod ) {
        
        int orig;
        int dest;
        int startTime;
        int mode;
        int o;
        int d;
        int allTripsCount=0;
        int tripCount=0;
        
        
        double[][] tripTable = new double[networkNumCentroids+1][networkNumCentroids+1];
        

        
        // read the PT output person trip list file into a TableDataSet
        OLD_CSVFileReader reader = new OLD_CSVFileReader();

        String[] columnsToRead = { "origin", "destination", "tripStartTime", "tripMode" };
        TableDataSet table = null;
        try {
            if ( fileName != null) {

                table = reader.readFile(new File( fileName ), columnsToRead);

                // traverse the trip list in the TableDataSet and aggregate trips to an o/d trip table
                for (int i=0; i < table.getRowCount(); i++) {
                    
                    orig = (int)table.getValueAt( i+1, "origin" );
                    dest = (int)table.getValueAt( i+1, "destination" );
                    startTime = (int)table.getValueAt( i+1, "tripStartTime" );
                    mode = (int)table.getValueAt( i+1, "tripMode" );
                    
                    o = nodeIndex[orig];
                    d = nodeIndex[dest];
                    
                    // accumulate all peak period highway mode trips
                    if ( (mode == ModeType.WALKTRANSIT || mode == ModeType.TRANSITPASSENGER) ) {
                        
                        if ( (startTime >= startPeriod && startTime <= endPeriod) ) {
    
                            tripTable[o][d]++;
                            tripCount++;
                        
                        }

                        allTripsCount++;
                    }
                    
                }
                
                // done with trip list TableDataSet
                table = null;

            }
            
        } catch (IOException e) {
            logger.error ( "", e );
        }


        logger.info (allTripsCount + " total walk transit trips read from PT file");
        logger.info (tripCount + " total walk transit trips read from PT file for period " + startPeriod +
                " to " + endPeriod);

        return tripTable;
            
    }
    

    private double[][] getDriveTransitTripTableFromPTList ( int[] nodeIndex, String fileName, int startPeriod, int endPeriod ) {
        
        int orig;
        int dest;
        int startTime;
        int mode;
        int o;
        int d;
        int allTripsCount=0;
        int tripCount=0;
        
        
        double[][] tripTable = new double[networkNumCentroids+1][networkNumCentroids+1];
        

        
        // read the PT output person trip list file into a TableDataSet
        OLD_CSVFileReader reader = new OLD_CSVFileReader();

        String[] columnsToRead = { "origin", "destination", "tripStartTime", "tripMode" };
        TableDataSet table = null;
        try {
            if ( fileName != null) {

                table = reader.readFile(new File( fileName ), columnsToRead);

                // traverse the trip list in the TableDataSet and aggregate trips to an o/d trip table
                for (int i=0; i < table.getRowCount(); i++) {
                    
                    orig = (int)table.getValueAt( i+1, "origin" );
                    dest = (int)table.getValueAt( i+1, "destination" );
                    startTime = (int)table.getValueAt( i+1, "tripStartTime" );
                    mode = (int)table.getValueAt( i+1, "tripMode" );
                    
                    o = nodeIndex[orig];
                    d = nodeIndex[dest];
                    
                    // accumulate all peak period highway mode trips
                    if ( (mode == ModeType.DRIVETRANSIT || mode == ModeType.PASSENGERTRANSIT) ) {
                        
                        if ( (startTime >= startPeriod && startTime <= endPeriod) ) {
    
                            tripTable[o][d]++;
                            tripCount++;
                        
                        }

                        allTripsCount++;
                    }
                    
                }
                
                // done with trip list TableDataSet
                table = null;

            }
            
        } catch (IOException e) {
            logger.error ( "", e );
        }


        logger.info (allTripsCount + " total drive transit trips read from PT file");
        logger.info (tripCount + " total drive transit trips read from PT file for period " + startPeriod +
                " to " + endPeriod);

        return tripTable;
            
    }
    

    private double[][][] getTruckAssignmentGroupTripTableFromCTList ( HashMap assignmentGroupMap, int[] nodeIndex, String fileName, int startPeriod, int endPeriod ) {

        int orig;
        int dest;
        int startTime;
        int mode;
        int o;
        int d;
        int group;
        char modeChar;
        String truckType;
        double tripFactor = 1.0;
        int allTruckTripCount=0;
        

        double[] tripsByUserClass = new double[highwayModeCharacters.length];
        double[] tripsByAssignmentGroup = new double[networkNumUserClasses];
        double[][][] tripTable = new double[networkNumUserClasses][networkNumCentroids+1][networkNumCentroids+1];



		// read the CT output file into a TableDataSet
		OLD_CSVFileReader reader = new OLD_CSVFileReader();

		TableDataSet table = null;
		int tripRecord = 0;
		try {
			if ( fileName != null ) {

				table = reader.readFile(new File( fileName ));

				// traverse the trip list in the TableDataSet and aggregate trips to an o/d trip table
				for (int i=0; i < table.getRowCount(); i++) {
					tripRecord = i+1;
					
					orig = (int)table.getValueAt( i+1, "origin" );
					dest = (int)table.getValueAt( i+1, "destination" );
					startTime = (int)table.getValueAt( i+1, "tripStartTime" );
					truckType = (String)table.getStringValueAt( i+1, "truckType" );
					tripFactor = (int)table.getValueAt( i+1, "tripFactor" );
	
					mode = Integer.parseInt( truckType.substring(3) );
					modeChar = highwayModeCharacters[mode];
					group = ((Integer)assignmentGroupMap.get( String.valueOf( modeChar ) )).intValue();
					
					o = nodeIndex[orig];
					d = nodeIndex[dest];
	
					// accumulate all peak period highway mode trips
					if ( startTime >= startPeriod && startTime <= endPeriod ) {
	
					    tripTable[group-1][o][d] += tripFactor;
					    tripsByUserClass[mode-1] += tripFactor;
					    tripsByAssignmentGroup[group-1] += tripFactor;
	
					}
                    
                    allTruckTripCount += tripFactor;
	
				}
	
				// done with trip list TabelDataSet
				table = null;

			}
			
		} catch (Exception e) {
			logger.error ("exception caught reading CT truck trip record " + tripRecord, e);
			System.exit(-1);
		}

		
        logger.info (allTruckTripCount + " total trips by all truck user classes read from CT file.");
        logger.info ("trips by truck user class read from CT file from " + startPeriod + " to " + endPeriod + ":");
		for (int i=0; i < tripsByUserClass.length; i++)
			if (tripsByUserClass[i] > 0)
				logger.info ( tripsByUserClass[i] + " truck trips with user class " + highwayModeCharacters[i+1] );

		logger.info ("trips by truck assignment groups read from CT file from " + startPeriod + " to " + endPeriod + ":");
		for (int i=0; i < tripsByAssignmentGroup.length; i++)
			if (tripsByAssignmentGroup[i] > 0)
				logger.info ( tripsByAssignmentGroup[i] + " truck trips in assignment group " + (i+1) );

		return tripTable;

    }
    
}
