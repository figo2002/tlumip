/* Generated by Together */

package com.pb.despair.ha;


import com.pb.despair.ld.*;
import com.pb.common.util.*;
import com.pb.common.datafile.TableDataSet;
import com.pb.models.pecas.AbstractTAZ;
import com.pb.models.pecas.DevelopmentTypeInterface;

import java.util.*;



public class PrepareHouseholdVariables {
    static int oldNumberOfHouseholds = 0;
    static int newNumberOfHouseholds = 0;
    static AllHouseholds ahh;
    static AbstractTAZ[] zones;
    static private String populationPath;
    static private String spaceTypePath;
    static private String householdCategoriesPath;
 //   static private Hashtable householdCategories = new Hashtable();

    public static void main(String[] args) {
        //final int numHouseholdsForTest = 1400;
        ResourceBundle rb = ResourceUtil.getResourceBundle( "ha" );
        populationPath = ResourceUtil.getProperty(rb, "population.path");
        spaceTypePath = ResourceUtil.getProperty(rb, "spaceType.path");
        householdCategoriesPath = ResourceUtil.getProperty(rb, "householdCategories.path");
  //      Household.useGridCells = (ResourceUtil.getProperty(rb, "useGridCells").equals("true"));
//        AppProperties appProps = PropertiesManager.getAppProperties("despair.properties");
        //int numTestZones = Integer.valueOf(appProps.getProperty("Model.numZones")).intValue();
        DevelopmentTypeInterface dtypes[] = setUpDevelopmentTypes();
        //TAZ.createTazArray(numTestZones);
        //for (int z = 0; z < numTestZones; z++) {
        //    TAZ.createTaz(z); // automatically puts it into the array based on the zone number z
        //}


        TableDataSet ztab = HAModel.reloadTableFromScratchFromTextFile(spaceTypePath,"Zones");
        TAZ.setUpZones(ztab);

        zones = TAZ.getAllZones();
        //Commodity.setUpCommodities(path);
        //Commodity.setUpExchangesAndZUtilities(path);
        
        ahh = AllHouseholds.getAllHouseholds(zones);
        Household.setAllHouseholds(ahh);
        Person.setAllHouseholds(ahh);
        // TODO set up TableDataSetCollection and read in develoment type usage
        // (old way used Borland JDatastore)
        // DevelopmentType.setUpDevelopmentTypeUsage(HAModel.dm);
        ahh.buildSpaceChoiceLogit();
        ahh.setUpVacationSizeTerms(spaceTypePath);
        ZoningScheme.setUpZoningSchemes(HAModel.reloadTableFromScratchFromTextFile(spaceTypePath,"ZoningSchemes"));

    //    TableDataSet gridCellTable = HAModel.reloadTableFromScratchFromTextFile(spaceTypePath,"GridCells");
    //    LDModel.setUpGridCells(gridCellTable);
        
        readInSpacePrices();
        ahh.setUpLaborPrices(spaceTypePath);


        AllHouseholds.prepareHouseholdVariables();
        HAModel.dm.closeStore();

        System.exit(0);
    }

    public static DevelopmentType[] setUpDevelopmentTypes() {
        TableDataSet ctab = HAModel.reloadTableFromScratchFromTextFile(spaceTypePath,"DevelopmentTypes");
        ArrayList dtypes = new ArrayList();
        for(int r=1;r<=ctab.getRowCount();r++){
            String typeName = (ctab.getStringValueAt(r,"DevelopmentTypeName"));
            boolean dynamic = (Boolean.valueOf(ctab.getStringValueAt(r,"DynamicPricesDevelopmentType"))).booleanValue();
            int gridCode = ctab.getStringValueAt(r,"GridCode").charAt(0);
            if (dynamic) {
                dtypes.add(new DynamicPricesDevelopmentType(typeName,
                    (double)ctab.getValueAt(r,"PortionVacantMultiplier"),
                    (double)ctab.getValueAt(r,"EquilibriumVacancyRate"),
                    (double)ctab.getValueAt(r,"MinimumBasePrice"),gridCode));
            } else {
                dtypes.add(new DevelopmentType(typeName,gridCode));
            }
            }
        DevelopmentType[] d = new DevelopmentType[dtypes.size()];
        return (DevelopmentType[]) dtypes.toArray(d);
    }

    static void reportStatus(float time) {
        System.out.println(ahh.reportPools());
        //  System.out.println("Size of new households pool =" + ahh.newHouseholdsPool.size());
        newNumberOfHouseholds = ahh.getEconomicUnits().size();
        //  Systemh.out.println("Number of households =" + newNumberOfHouseholds);
        int population = 0;
        Household[] meu = new Household[newNumberOfHouseholds];
        meu = (Household[]) ahh.getEconomicUnits().toArray(meu);
        for (int hhnum = 0; hhnum < meu.length; hhnum++) {
            Household hh = meu[hhnum];
            population += hh.getPeople().size();
/*            if (hh.getPrimaryLocation() == null) { } //System.out.println(hh+" doesn't have a home");
            else
                floorspaceUsed[hh.getHomeZone().getZoneIndex()] += hh.getPrimaryLocation().getSize();
            if (hh.getSecondaryLocation() != null)
                floorspaceUsed[hh.getSecondaryZone().getZoneIndex()] += hh.getSecondaryLocation().getSize();
                */
        }
        System.out.println("Population = " + population);
/*        for (int z = 0; z < floorspaceUsed.length; z++) {
            //      System.out.println("Zone "+z+" floorspace used "+floorspaceUsed[z]);
        } */
        // TODO log development status to CSV file
        //DevelopmentType.writeStatusForAll(HAModel.dm, time);
        oldNumberOfHouseholds = newNumberOfHouseholds;
    }


//    public static void readInHouseholdCategories() {
//        TableDataSet tab = HAModel.reloadTableFromScratchFromTextFile(spaceTypePath,"HouseholdCategories");
//        while (tab.inBounds()) {
//            String typeName = (tab.getString("CategoryName"));
//            FixedSizeHouseholdCategory category = (FixedSizeHouseholdCategory) householdCategories.get(typeName);
//            if (category == null) {
//            	category = new FixedSizeHouseholdCategory();
//            	householdCategories.put(typeName,category);
//            }
//            FixedSizeHouseholdCategory.PersonDescriptor pd = new FixedSizeHouseholdCategory.PersonDescriptor();
//            pd.minAge = tab.getInt("MinAge");
//            pd.maxAge = tab.getInt("MaxAge");
//            pd.gender = tab.getString("Gender").charAt(0);
//            pd.fullTimeEmployedProbability = tab.getDouble("FullTimeEmployedProbability");
//            pd.partTimeEmployedProbability = tab.getDouble("PartTimeEmployedProbability");
//            pd.minEducation = tab.getInt("MinEducation");
//            pd.maxEducation = tab.getInt("MaxEducation");
//            category.addPerson(pd);
//        	tab.next();
//        }
//    }
//    	
//    public static void setHouseholdCategories(final AllHouseholds ahh) {
//    	readInHouseholdCategories();
//        ahh.newHouseholdProbabilities = new HouseholdPossibilities();
//        ahh.joiningUpProbabilities = new HouseholdPossibilities();
//        TableDataSet tab = HAModel.reloadTableFromScratchFromTextFile(spaceTypePath,"HouseholdPossibilities");
//        while (tab.inBounds()) {
//            String typeName = (tab.getString("JorN"));
//            	String categoryName = tab.getString("CategoryName");
//            	double weight = tab.getDouble("Weight");
//            	HouseholdCategory category = (HouseholdCategory) householdCategories.get(categoryName);
//            	if (category==null) throw new RuntimeException("Invalid household category name in householdpossibilities.csv: "+categoryName);
//            if (typeName.equals("J")) {
//            	ahh.joiningUpProbabilities.add(category,weight);
//            }
//            if (typeName.equals("N")) {
//            	ahh.newHouseholdProbabilities.add(category,weight);
//            }
//        	tab.next();
//        }
//    }
    

    public static void readInSpacePrices() {
        TableDataSet tab = HAModel.reloadTableFromScratchFromTextFile(spaceTypePath,"FloorspaceRents");
        for(int r=1;r<=tab.getRowCount();r++) {
            String typeName = tab.getStringValueAt(r,"DevelopmentType");
            DevelopmentTypeInterface dt = DevelopmentType.getAlreadyCreatedDevelopmentType(typeName);
            int zone = (int)tab.getValueAt(r,"Zone");
            double price = (double)tab.getValueAt(r,"Price");
            AbstractTAZ taz = AbstractTAZ.findZoneByUserNumber(zone);
            if (taz == null) System.out.println("Bad zone number in space prices "+zone);
            else AbstractTAZ.findZoneByUserNumber(zone).updatePrice(dt,price);
         }
    }
    
}
