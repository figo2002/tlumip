package com.pb.despair.pi;

import com.pb.common.datafile.TableDataSet;
import com.pb.common.datafile.CSVFileReader;
import com.pb.common.datafile.JDBCTableReader;
import com.pb.common.util.ResourceUtil;
import com.pb.common.matrix.Matrix;
import com.pb.common.matrix.MatrixHistogram;
import com.pb.common.matrix.ZipMatrixWriter;
import com.pb.common.sql.JDBCConnection;
import com.pb.despair.model.*;

import java.util.*;
import java.util.logging.Logger;
import java.io.*;

/**
 * This class is responsible for reading in the PI input files
 * and setting up objects used by PIModel.  It is created by the PIControl
 * class which is responsible for setting the ResourceBundle and the timePeriod
 *
 * @author Christi Willison
 * @version Mar 17, 2004
 */
public class PIPProcessor {
    protected static Logger logger = Logger.getLogger("com.pb.despair.pi.PIReader");
    //the following 2 params are set by the PIControl's constructor which is called
    //when PI is run in monolithic fashion.  Otherwise, these params are passed to
    //the PIPProcessor constructor when called from PIDAF (see PIServerTask's 'onStart( )' method)
    private int timePeriod;
    protected ResourceBundle rb;

    private TAZ[] zones; //will be initialized in setUpZones method after length has been determined.

    private String outputPath = null;
    Hashtable floorspaceInventory;
    Hashtable floorspaceZoneCrossref;
    int maxAlphaZone=0;

    public PIPProcessor(){
    }

    public PIPProcessor(int timePeriod, ResourceBundle rb){
        this.timePeriod = timePeriod;
        this.rb = rb;
    }

    public void setUpPi() {
        setUpZones(); // read in PECASZonesI.csv and creates a 'Zone' table data set
        setUpProductionActivities(); //read in ActivitiesI.csv and initalize an array of aggregate activities
        String[] skims = setUpCommodities(); //read in CommoditiesI.csv and initalize an array of commodities
        setUpExchangesAndZUtilities();//read in ExchangeImportExportI.csv, create an 'Exchanges'
        //table data set and link the exchanges to commodities.
        setExchangePrices();
        setUpTransportConditions(skims); //read in betapktime.zip and betapkdist.zip
        // and create a PeakAutoSkims object
        setUpMakeAndUse();//read in MakeUseI.csv and create a 'MakeUse' table data set.
        readFloorspaceZones();
        readFloorspace();
        recalcActivitySizeTerms();
        recalcFloorspaceBuyingSizeTerms();
        recalcFloorspaceImport();
    }
    
    protected  void readFloorspace() {
        logger.info("Reading Floorspace File");
        if (maxAlphaZone == 0) readFloorspaceZones();
        TableDataSet floorspaceTable = loadTableDataSet("FloorspaceI","pi.base.data");
        floorspaceInventory = new Hashtable();
        int alphaZoneColumn = floorspaceTable.checkColumnPosition("FloorspaceZone");
        int quantityColumn = floorspaceTable.checkColumnPosition("Quantity");
        int floorspaceTypeColumn = floorspaceTable.checkColumnPosition("Commodity");
        for (int row = 1; row <= floorspaceTable.getRowCount(); row++) {
            int alphaZone = (int) floorspaceTable.getValueAt(row,alphaZoneColumn);
            float quantity = floorspaceTable.getValueAt(row,quantityColumn);
            String commodityName = floorspaceTable.getStringValueAt(row,floorspaceTypeColumn);
            Commodity c = Commodity.retrieveCommodity(commodityName); 
            if (c==null) throw new Error("Bad commodity name "+commodityName+" in floorspace inventory table");
            FloorspaceQuantityStorage fi = (FloorspaceQuantityStorage) floorspaceInventory.get(commodityName);
            if (fi==null) {
                fi = new FloorspaceQuantityStorage(commodityName,maxAlphaZone+1);
                floorspaceInventory.put(commodityName,fi);
            }
            fi.inventory[alphaZone]+= quantity;
        }
    }
    

    protected void setUpZones() {
        logger.info("Setting up Zones");
        TableDataSet ztab = loadTableDataSet("PECASZonesI","pi.base.data");
        //inner class to set up the name/number pair of each exchange zone
        //referred to here as a TAZ but in actuality they are beta zones.
        class NumberName {
            String zoneName;
            int zoneNumber;

            public NumberName(int znum, String zname) {
                zoneName = zname;
                zoneNumber = znum;
            }
        };
        //Reads the betazone table, creates a NumberName object that
        //is temporarily stored in an array list.  Seems like
        //this step could be skipped, the tempZoneStorage.size() = ztab.getRowCount()
        //so you could create the AbstractTAZ array, then read in each row and create
        //the TAZ on the fly and store it in the array.  That would eliminate
        //the call to the array list for the NumberName object.
        ArrayList tempZoneStorage = new ArrayList();
        for (int row = 1; row <= ztab.getRowCount(); row++) {
            String zoneName = ztab.getStringValueAt(row, "ZoneName");
            int zoneNumber = (int) ztab.getValueAt(row, "ZoneNumber");
            NumberName nn = new NumberName(zoneNumber, zoneName);
            tempZoneStorage.add(nn);
        }
        //this creates an empty AbstractTAZ[] which is in fact an array of beta zones which
        //are the exchange zones refered to throughout the PI Model
        TAZ.createTazArray(tempZoneStorage.size());
        for (int z = 0; z < tempZoneStorage.size(); z++) {
            NumberName nn = (NumberName) tempZoneStorage.get(z);
            //this creates a TAZ object and stores it in the AbstractTAZ array
            TAZ.createTaz(z, nn.zoneNumber, nn.zoneName);
        }
        //Once the array of AbstractTAZ is created it is then copied back into
        //an array of TAZ objects. (the TAZ class is in the despair.pi package
        //whereas the AbstractTAZ class is in the despair.model package.
        //My thought is that we could get rid of the AbstractTAZ[] initialization
        //as I think all the relevent properties are set in the TAZ objects.

        AbstractTAZ[] allZones = AbstractTAZ.getAllZones();
        zones = new TAZ[allZones.length];
        for (int z = 0; z < allZones.length; z++) {
            zones[z] = (TAZ) allZones[z];
        }
    }

    protected void setUpProductionActivities() {
        logger.info("Setting up Production Activities");
        boolean logitProduction = (ResourceUtil.getProperty(rb, "pi.useLogitProduction").equalsIgnoreCase("true"));
        if (logitProduction) logger.fine("using logit substitution production function");
        int numMissingZonalValueErrors = 0;
        TableDataSet ptab = null;
        TableDataSet zonalData = null;
        String oregonInputsString = ResourceUtil.getProperty(rb, "pi.oregonInputs");
        if (oregonInputsString != null ) {
            if (oregonInputsString.equalsIgnoreCase("true")) {
                ptab= loadTableDataSet("ActivitiesW","pi.current.data");
                zonalData = loadTableDataSet("ActivitiesZonalValuesW","pi.current.data");
            } else {
                ptab = loadTableDataSet("ActivitiesI","pi.base.data");
                zonalData = loadTableDataSet("ActivitiesZonalValuesI","pi.base.data");
            }
        }else {
            ptab = loadTableDataSet("ActivitiesI","pi.base.data");
            zonalData = loadTableDataSet("ActivitiesZonalValuesI","pi.base.data");
        }
        
        Hashtable activityZonalHashtable = new Hashtable();
        for (int zRow = 1; zRow <= zonalData.getRowCount(); zRow++) {
            String activityZone = zonalData.getStringValueAt(zRow, "Activity") + "@" + ((int) zonalData.getValueAt(zRow, "ZoneNumber"));
            activityZonalHashtable.put(activityZone, new Integer(zRow));
        }


        for (int pRow = 1; pRow <= ptab.getRowCount(); pRow++) {
            String activityName = ptab.getStringValueAt(pRow, "Activity");
            logger.fine("Setting up production activity " + activityName);
            AggregateActivity aa =
                    new AggregateActivity(activityName, zones);
            aa.setLocationDispersionParameter(ptab.getValueAt(pRow, "LocationDispersionParameter"));
            aa.setSizeTermCoefficient(ptab.getValueAt(pRow, "SizeTermCoefficient"));
            aa.setTotalAmount(ptab.getValueAt(pRow, "Size"));
            if (logitProduction) {
/// this change won't seem to stick in CVS!!!
                LogitSubstitution pf =new LogitSubstitution(ptab.getValueAt(pRow, "ProductionUtilityScaling"),
                        ptab.getValueAt(pRow, "ProductionSubstitutionNesting"));
                if (ptab.getStringValueAt(pRow, "NonModelledProduction").equalsIgnoreCase("true")) {
                    pf.addNonModelledAlternative(ptab.getValueAt(pRow, "UtilityOfNonModelledProduction"));
                }
                LogitSubstitution cf = new LogitSubstitution(ptab.getValueAt(pRow, "ConsumptionUtilityScaling"),
                        ptab.getValueAt(pRow, "ConsumptionSubstitutionNesting"));
                if (ptab.getStringValueAt(pRow, "NonModelledConsumption").equalsIgnoreCase("true")) {
                    pf.addNonModelledAlternative(ptab.getValueAt(pRow, "UtilityOfNonModelledConsumption"));
                }
                aa.setProductionFunction(pf);
                aa.setConsumptionFunction(cf);
            } else {
                aa.setProductionFunction(new ExponentialQuantities());
                aa.setConsumptionFunction(new ExponentialQuantities());
            }
            for (int z = 0; z < zones.length; z++) {
                String zoneDataKey = activityName + "@" + zones[z].getZoneUserNumber();
                Integer zoneDataIndex = (Integer) activityZonalHashtable.get(zoneDataKey);
                if (zoneDataIndex != null) {
                    aa.setDistribution(zones[z],
                            zonalData.getValueAt(zoneDataIndex.intValue(), "InitialQuantity"),
                            zonalData.getValueAt(zoneDataIndex.intValue(), "SizeTerm"),
                            zonalData.getValueAt(zoneDataIndex.intValue(),
                                    "ZoneConstant"));

                } else {
                    if (++numMissingZonalValueErrors < 20) {
                        logger.warning("Can't locate zonal data for AggregateActivity "+ aa
                                + " zone "+ zones[z].getZoneUserNumber()
                                + " using size term 1.0, quantity 0.0, location ASC 0.0");
                    }
                    if (numMissingZonalValueErrors == 20) logger.warning("Surpressing further errors on missing zonal data");
                    aa.setDistribution(zones[z], 0.0, 1.0, 0.0);
                }
            }
        }
    }

    /**
     * Sets up commodities by reading in CommoditiesI.csv
     * @return array of names of skims
     */
    protected String[] setUpCommodities() {
        ArrayList skimNames = new ArrayList();
        logger.info("Setting up Commodities");
        TableDataSet ctab=loadTableDataSet("CommoditiesI","pi.base.data");
        int nameColumn = ctab.checkColumnPosition("Commodity");
        int bdpColumn = ctab.checkColumnPosition("BuyingDispersionParameter");
        int sdpColumn = ctab.checkColumnPosition("SellingDispersionParameter");
        int bscColumn = ctab.checkColumnPosition("BuyingSizeCoefficient");
        int bpcColumn = ctab.checkColumnPosition("BuyingPriceCoefficient");
        int btcColumn = ctab.checkColumnPosition("BuyingTransportCoefficient");
        int sscColumn = ctab.checkColumnPosition("SellingSizeCoefficient");
        int spcColumn = ctab.checkColumnPosition("SellingPriceCoefficient");
        int stcColumn = ctab.checkColumnPosition("SellingTransportCoefficient");
        int exchangeTypeColumn = ctab.checkColumnPosition("ExchangeType");
        int skimCoef1C = ctab.checkColumnPosition("InterchangeCoefficient1");
        int skimName1C = ctab.checkColumnPosition("InterchangeName1") ;
        int skimCoef2C = ctab.getColumnPosition("InterchangeCoefficient2");
        int skimName2C = ctab.getColumnPosition("InterchangeName2") ;
        int skimCoef3C = ctab.getColumnPosition("InterchangeCoefficient3");
        int skimName3C = ctab.getColumnPosition("InterchangeName3") ;
        int floorspaceTypeC = ctab.checkColumnPosition("FloorspaceCommodity");
        int expectedPriceC = ctab.checkColumnPosition("ExpectedPrice");
        for (int row = 1; row <= ctab.getRowCount(); row++) {
            String commodityName = ctab.getStringValueAt(row, nameColumn);
            float defaultBuyingDispersionParameter = ctab.getValueAt(row, bdpColumn);
            float defaultSellingDispersionParameter = ctab.getValueAt(row, sdpColumn);
            float buyingSizeCoefficient = ctab.getValueAt(row, bscColumn);
            float buyingPriceCoefficient = ctab.getValueAt(row, bpcColumn);
            float buyingTransportCoefficient = ctab.getValueAt(row, btcColumn);
            float sellingSizeCoefficient = ctab.getValueAt(row, sscColumn);
            float sellingPriceCoefficient = ctab.getValueAt(row, spcColumn);
            float sellingTransportCoefficient = ctab.getValueAt(row, stcColumn);
            boolean isFloorspace = ctab.getStringValueAt(row,floorspaceTypeC).equalsIgnoreCase("true"); 
            Commodity c = Commodity.createOrRetrieveCommodity(commodityName, ctab.getStringValueAt(row, exchangeTypeColumn).charAt(0));
            c.setDefaultBuyingDispersionParameter(defaultBuyingDispersionParameter);
            c.setDefaultSellingDispersionParameter(defaultSellingDispersionParameter);
            c.setBuyingUtilityCoefficients(buyingSizeCoefficient, buyingPriceCoefficient, buyingTransportCoefficient);
            c.setSellingUtilityCoefficients(sellingSizeCoefficient, sellingPriceCoefficient, sellingTransportCoefficient);
            c.setFloorspaceCommodity(isFloorspace);
            LinearFunctionOfSomeSkims lfoss = new LinearFunctionOfSomeSkims();
            String skimName = ctab.getStringValueAt(row,skimName1C);
            lfoss.addSkim(skimName,ctab.getValueAt(row,skimCoef1C));
            if (!skimNames.contains(skimName)) skimNames.add(skimName);
            if (skimCoef2C>0) {
                skimName = ctab.getStringValueAt(row,skimName2C);
                if (skimName.length()!=0 && skimName.trim().length()!=0) {
                    lfoss.addSkim(skimName,ctab.getValueAt(row,skimCoef2C));
                    if (!skimNames.contains(skimName)) skimNames.add(skimName);
                }
            }
            if (skimCoef3C>0) {
                skimName = ctab.getStringValueAt(row,skimName3C);
                if (skimName.length()!=0 && skimName.trim().length()!=0) {
                    lfoss.addSkim(skimName,ctab.getValueAt(row,skimCoef3C));
                    if (!skimNames.contains(skimName)) skimNames.add(skimName);
                }
            }
            c.setCommodityTravelPreferences(lfoss);
            c.compositeMeritMeasureWeighting = ctab.getValueAt(row, "GOFWeighting");
            c.setExpectedPrice(ctab.getValueAt(row,expectedPriceC));

        }
        return (String[]) skimNames.toArray(new String[0]);
    }
    
    ArrayList histogramSpecifications = new ArrayList();
    static class HistogramSpec {
        String commodityName;
        String categorizationSkim;
        private Commodity com = null;
        /**
         * <code>boundaries</code> contains Float objects describing the histogram band boundaries
         */
        ArrayList boundaries = new ArrayList();
        
        Commodity getCommodity() {
            if (com == null) {
                com = Commodity.retrieveCommodity("commodityName");
            }
            return com;
        }
    }
    private final int maxHistogramBands = 100;
    
    protected String[] readInHistogramSpecifications() {
         ArrayList newSkimNames = new ArrayList();
         TableDataSet histogramSpecTable = loadTableDataSet("HistogramsI","pi.base.data");
         for (int row = 1; row <= histogramSpecTable.getRowCount(); row++ ) {
             HistogramSpec hspec = new HistogramSpec();
             hspec.commodityName = histogramSpecTable.getStringValueAt(row,"Commodity");
             hspec.categorizationSkim = histogramSpecTable.getStringValueAt(row,"Skim");
             if (!newSkimNames.contains(hspec.categorizationSkim)) newSkimNames.add(hspec.categorizationSkim);
             float lastHistogramBoundary = Float.NEGATIVE_INFINITY;
             for (int i=0;i<maxHistogramBands;i++) {
                 int column = histogramSpecTable.getColumnPosition("C"+i);
                 if (column >0) {
                     float boundary = histogramSpecTable.getValueAt(row,column);
                     if (boundary > lastHistogramBoundary) {// force increasing boudaries
                         hspec.boundaries.add(new Float(boundary));
                         lastHistogramBoundary = boundary;
                     }
                 }
             }
             histogramSpecifications.add(hspec);
         }
         String[] bob = new String[newSkimNames.size()];
         bob = (String []) newSkimNames.toArray(bob);
         return bob;
    }
    
    protected void readFloorspaceZones() {
        logger.info("Reading Floorspace Zones");
        floorspaceZoneCrossref = new Hashtable();
        if (ResourceUtil.getProperty(rb,"pi.useFloorspaceZones").equalsIgnoreCase("true")) {
            TableDataSet alphaZoneTable = loadTableDataSet("FloorspaceZonesI","pi.base.data");
            for (int zRow = 1; zRow <= alphaZoneTable.getRowCount(); zRow++) {
                Integer floorspaceZone = new Integer( (int) alphaZoneTable.getValueAt(zRow, "FloorspaceZone"));
                int pecasZoneInt = (int) alphaZoneTable.getValueAt(zRow,"PecasZone");
                AbstractTAZ pecasZone = AbstractTAZ.findZoneByUserNumber(pecasZoneInt);
                if (pecasZone != null) {
                    // don't add in bogus records -- there might be land use zones that aren't covered by the spatial IO model
                    Integer pecasZoneInteger = new Integer(pecasZoneInt);
                    floorspaceZoneCrossref.put(floorspaceZone,pecasZoneInteger);
                } else {
                    logger.warning("Bad spatial IO zone number "+pecasZoneInt+" in FloorspaceZonesI ... ignoring land use zone "+floorspaceZone.intValue());
                }
            }
        } else {
            logger.info("Not using floorspace zones -- floorspace zones are the same as PECAS zones");
            for (int z= 0; z<zones.length;z++) {
                Integer zoneNumber = new Integer(zones[z].getZoneUserNumber());
                floorspaceZoneCrossref.put(zoneNumber,zoneNumber);
            }
        }
        maxAlphaZone = maxAlphaZone();
    }
    
    public int maxAlphaZone() {
        maxAlphaZone = 0;
        Enumeration it = floorspaceZoneCrossref.keys();
        while (it.hasMoreElements()) {
            Integer alphaZoneNumber = (Integer) it.nextElement();
            if (alphaZoneNumber.intValue()>maxAlphaZone) {
                maxAlphaZone = alphaZoneNumber.intValue();
            }
        }
        return  maxAlphaZone;
    }
    
    protected static class FloorspaceQuantityStorage {
        final String floorspaceTypeName;
        final float [] inventory;
        
        FloorspaceQuantityStorage(String commodityName, int arraySize) {
            floorspaceTypeName = commodityName;
            inventory = new float[arraySize];
        }
        
        public Commodity getFloorspaceType() {
            return Commodity.retrieveCommodity(floorspaceTypeName);
        }
    }
    
    protected void setUpExchangesAndZUtilities() {
        logger.info("Setting up Exchanges and ZUtilitites");
        int numExchangeNotFoundErrors=0;
        TableDataSet exchanges = loadTableDataSet("ExchangeImportExportI","pi.base.data");
        HashMap exchangeTempStorage = new HashMap();
        class ExchangeInputData {
            String commodity;
            int zone;
            float buyingSize;
            float sellingSize;
            boolean specifiedExchange;
            char exchangeType;
            float importFunctionMidpoint;
            float importFunctionMidpointPrice;
            float importFunctionLambda;
            float importFunctionDelta;
            float importFunctionSlope;
            float exportFunctionMidpoint;
            float exportFunctionMidpointPrice;
            float exportFunctionLambda;
            float exportFunctionDelta;
            float exportFunctionSlope;
            boolean monitorExchange;
            double price;

        }
        int priceColumn = exchanges.getColumnPosition("Price");
        if (priceColumn == -1) logger.info("No price data in ExchangeImportExport table");
        int monitorColumn = exchanges.getColumnPosition("MonitorExchange");
        if (monitorColumn == -1) logger.warning("No MonitorExchange column in ExchangeImportExport table -- not monitoring any exchanges");
        for (int row = 1; row <= exchanges.getRowCount(); row++) {
            String key = exchanges.getStringValueAt(row, "Commodity") + "$" + String.valueOf((int) exchanges.getValueAt(row, "ZoneNumber"));
            ExchangeInputData ex = new ExchangeInputData();
            ex.commodity = exchanges.getStringValueAt(row, "Commodity");
            ex.zone = (int) exchanges.getValueAt(row, "ZoneNumber");
            ex.buyingSize = exchanges.getValueAt(row, "BuyingSize");
            ex.sellingSize = exchanges.getValueAt(row, "SellingSize");
            String ses = exchanges.getStringValueAt(row, "SpecifiedExchange");
            if (ses.equalsIgnoreCase("true")) {
                ex.specifiedExchange = true;
            } else {
                ex.specifiedExchange = false;
            }
            ex.importFunctionMidpoint = exchanges.getValueAt(row, "ImportFunctionMidpoint");
            ex.importFunctionMidpointPrice = exchanges.getValueAt(row, "ImportFunctionMidpointPrice");
            ex.importFunctionLambda = exchanges.getValueAt(row, "ImportFunctionEta");
            ex.importFunctionDelta = exchanges.getValueAt(row, "ImportFunctionDelta");
            ex.importFunctionSlope = exchanges.getValueAt(row, "ImportFunctionSlope");
            ex.exportFunctionMidpoint = exchanges.getValueAt(row, "ExportFunctionMidpoint");
            ex.exportFunctionMidpointPrice = exchanges.getValueAt(row, "ExportFunctionMidpointPrice");
            ex.exportFunctionLambda = exchanges.getValueAt(row, "ExportFunctionEta");
            ex.exportFunctionDelta = exchanges.getValueAt(row, "ExportFunctionDelta");
            ex.exportFunctionSlope = exchanges.getValueAt(row, "ExportFunctionSlope");
            if (monitorColumn == -1)
                ex.monitorExchange = false;
            else {
                String monitor = exchanges.getStringValueAt(row, monitorColumn);
                if (monitor.equalsIgnoreCase("true")) {
                    ex.monitorExchange = true;
                } else {
                    ex.monitorExchange = false;
                }
            }
            if (priceColumn != -1)
                ex.price = exchanges.getValueAt(row, priceColumn);
            else
                ex.price = Commodity.retrieveCommodity(ex.commodity).getExpectedPrice();
            exchangeTempStorage.put(key, ex);
        }
        Iterator comit = Commodity.getAllCommodities().iterator();
        while (comit.hasNext()) {
            Commodity c = (Commodity) comit.next();
            for (int z = 0; z < zones.length; z++) {
                SellingZUtility szu = new SellingZUtility(c, zones[z], zones.length, c.getCommodityTravelPreferences());
                BuyingZUtility bzu = new BuyingZUtility(c, zones[z], zones.length, c.getCommodityTravelPreferences());
                szu.setDispersionParameter(c.getDefaultSellingDispersionParameter());
                bzu.setDispersionParameter(c.getDefaultBuyingDispersionParameter());
                String key = c.name + "$" + zones[z].getZoneUserNumber();
                ExchangeInputData exData = (ExchangeInputData) exchangeTempStorage.get(key);
                boolean found = true;
                if (exData == null) {
                    // try to find the default values for this commodity
                    key = c.name + "$" + "-1";
                    exData = (ExchangeInputData) exchangeTempStorage.get(key);
                    if (exData == null) {
                        found = false;
                    } else {
                        logger.finer("Using default exchange data for commodity " + c + " zone " + zones[z].getZoneUserNumber());
                    }
                }
                boolean specifiedExchange = false;
                if (found) {
                    specifiedExchange = exData.specifiedExchange;
                }
                if (c.exchangeType != 's' || specifiedExchange) {
                    Exchange xc;
                    if (c.exchangeType == 'n')
                        xc = new NonTransportableExchange(c, zones[z]);
                    else
                        xc = new Exchange(c, zones[z], zones.length);
                    if (!found) {//backup default data.
                        if (++numExchangeNotFoundErrors < 20)
                            logger.warning("Can't locate size term for Commodity " + c + " zone " +
                                    zones[z].getZoneUserNumber() + " using 1.0 for size terms and setting imports/exports to zero");
                        if (numExchangeNotFoundErrors == 20) logger.warning("Surpressing further warnings on missing size terms");
                        xc.setBuyingSizeTerm(1.0);
                        xc.setSellingSizeTerm(1.0);
                        xc.setImportFunction(Commodity.zeroFunction);
                        xc.setExportFunction(Commodity.zeroFunction);
                        xc.setPrice(1.0);
                    } else {
                        xc.setBuyingSizeTerm(exData.buyingSize);
                        xc.setSellingSizeTerm(exData.sellingSize);
                        xc.setImportFunction(new LogisticPlusLinearFunction(exData.importFunctionMidpoint,
                                exData.exportFunctionMidpointPrice,
                                exData.importFunctionLambda,
                                exData.importFunctionDelta,
                                exData.importFunctionSlope));
                        xc.setExportFunction(new LogisticPlusLinearFunction(exData.exportFunctionMidpoint,
                                exData.exportFunctionMidpointPrice,
                                exData.exportFunctionLambda,
                                exData.exportFunctionDelta,
                                exData.exportFunctionSlope));
                        if (exData.monitorExchange) {
                            xc.monitor = true;
                        }
                        xc.setPrice(exData.price);
                    }
                    if (c.exchangeType == 'p' || c.exchangeType == 'a' || c.exchangeType == 'n') szu.addExchange(xc);
                    if (c.exchangeType == 'c' || c.exchangeType == 'a' || c.exchangeType == 'n') bzu.addExchange(xc);
                }
            }
            logger.fine("Created all exchanges for commodity " + c + " -- now linking exchanges to production and consumption for " + zones.length + " zones");
            for (int z = 0; z < zones.length; z++) {
                if (z % 100 == 0) logger.finer(" " + z + "(" + zones[z].getZoneUserNumber() + ")");
                if (c.exchangeType == 'c' || c.exchangeType == 's' || c.exchangeType == 'a') {
                    CommodityZUtility czu = (CommodityZUtility) c.sellingTazZUtilities.get(zones[z]);
//                    CommodityZUtility czu = (CommodityZUtility) zones[z].getSellingCommodityZUtilities().get(c); // get the selling zutility again
                    czu.addAllExchanges(); // add in all the other exchanges for that commodity
                }
                if (c.exchangeType == 'p' || c.exchangeType == 's' || c.exchangeType == 'a') {
                    CommodityZUtility czu = (CommodityZUtility) c.buyingTazZUtilities.get(zones[z]);
//                    CommodityZUtility czu = (CommodityZUtility) zones[z].getBuyingCommodityZUtilities().get(c); // get the buying zutility again
                    czu.addAllExchanges(); // add in all the other exchanges for that commodity
                }
            }

        }
    }

    protected void setExchangePrices() {
        logger.info("Getting Exchange Prices");
        TableDataSet initialPrices = loadTableDataSet("ExchangeResultsI","pi.current.data");
        if (initialPrices == null) {
            logger.warning("No special price data for current year, check for previous year prices");
            initialPrices = loadTableDataSet("ExchangeResults","pi.previous.data");
            if(initialPrices == null){
                logger.warning("No previous year ExchangeResults to get prices from");
                return;
            }
            logger.info("ExchangeResults from the previous year have been found");
        }
        int commodityNameColumn = initialPrices.checkColumnPosition("Commodity");
        int priceColumn = initialPrices.checkColumnPosition("Price");
        int zoneNumberColumn = initialPrices.checkColumnPosition("ZoneNumber");
        if (commodityNameColumn * priceColumn * zoneNumberColumn <= 0) {
            logger.severe("Missing column in Exchange Results -- check for Commodity, Price and ZoneNumber columns.  Continuing without initial prices");
        }
        for (int row = 1; row <= initialPrices.getRowCount(); row++) {
            String cname = initialPrices.getStringValueAt(row, commodityNameColumn);
            Commodity com = Commodity.retrieveCommodity(cname);
            if (com == null) {
                logger.severe("Invalid commodity name " + cname + " in exchange results input price table");
            } else {
                int zoneUserNumber = (int) initialPrices.getValueAt(row, zoneNumberColumn);
                int zoneIndex = TAZ.findZoneByUserNumber(zoneUserNumber).getZoneIndex();
                Exchange x = com.getExchange(zoneIndex);
                if (x == null) {
                    logger.warning("No exchange for " + cname + " in " + zoneUserNumber + " check price input file");
                } else {
                    x.setPrice(initialPrices.getValueAt(row, priceColumn));
                }
            }
        }
        return;
    }


    protected void setUpTransportConditions(String[] skimNames) {
        logger.info("Setting up Transport Conditions");
        String skimFormat = ResourceUtil.getProperty(rb, "Model.skimFormat");
        if (skimFormat.equals("TableDataSet")) {
            String path = ResourceUtil.getProperty(rb, "skim.data");
            try {
                CSVFileReader reader = new CSVFileReader();
                reader.setDelimSet(" ,\t\n\r\f\"");
                TableDataSet s = reader.readFile(new File(path + "TIMEDIST.CSV"), false);
                SomeSkims someSkims = new SomeSkims();
                s.setColumnLabels(new String[] {"origin","destination","pktime","pkdistance"});
                TransportKnowledge.globalTransportKnowledge = someSkims;
                someSkims.addTableDataSetSkims(s,skimNames,10000);
            } catch (IOException e) {
                logger.severe("Error loading in skims");
                e.printStackTrace();
            }

        } else {
            String path1 = ResourceUtil.getProperty(rb, "skim.data");
            String path2 = ResourceUtil.getProperty(rb, "skim.data1");
            
            SomeSkims someSkims = new SomeSkims(path1, path2);
            TransportKnowledge.globalTransportKnowledge = someSkims;
            for (int s=0;s<skimNames.length;s++) {
                someSkims.addZipMatrix(skimNames[s]);
            }
        }
    }

    protected void setUpMakeAndUse() {
        logger.info("Setting up Make/Use table");
        TableDataSet makeUse = loadTableDataSet("MakeUseI","pi.base.data");
        for (int muRow = 1; muRow <= makeUse.getRowCount(); muRow++) {
            String activityName = makeUse.getStringValueAt(muRow, "Activity");
            String commodityName = makeUse.getStringValueAt(muRow, "Commodity");
            Iterator pit = ProductionActivity.getAllProductionActivities().iterator();
            boolean found = false;
            while (pit.hasNext() && !found) {
                ProductionActivity pa = (ProductionActivity) pit.next();
                if (pa.name.equals(activityName)) {
                    found = true;
                    Commodity c =
                            Commodity.retrieveCommodity(commodityName);
                    if (c == null) {
                        logger.severe("Incorrect commodity name \""
                                + commodityName
                                + "\" in MakeUse.csv");
                        throw new RuntimeException("Incorrect commodity name \""
                                + commodityName
                                + "\" in MakeUse.csv");
                    }
                    if (makeUse.getStringValueAt(muRow, "MorU").equals("U")) {
                        ConsumptionFunction cf =
                                pa.getConsumptionFunction();
                        if (cf instanceof ExponentialQuantities) {
                            ExponentialQuantities exq =
                                    (ExponentialQuantities) cf;
                            exq.addCommodity(new ExponentialQuantities.Quantity(c,
                                    makeUse.getValueAt(muRow, "Minimum"),
                                    makeUse.getValueAt(muRow,
                                            "OutsideExpMultiplier"),
                                    makeUse.getValueAt(muRow, "UtilityMultiplier"),
                                    makeUse.getValueAt(muRow, "UtilityOffset"),
                                    makeUse.getValueAt(muRow,
                                            "ImpactOnOverallUtility")));
                        } else if (cf instanceof LogitSubstitution) {
                            LogitSubstitution ls = (LogitSubstitution) cf;
                            ls.addCommodity(new LogitSubstitution.Quantity(c,
                                    makeUse.getValueAt(muRow, "Minimum"),
                                    makeUse.getValueAt(muRow, "Discretionary"),
                                    makeUse.getValueAt(muRow, "UtilityScale"),
                                    makeUse.getValueAt(muRow, "UtilityOffset")));
                        } else {
                            throw new RuntimeException("Don't know how to read in consumption function of type	"
                                    + cf.getClass());
                        }
                    } else if (makeUse.getStringValueAt(muRow, "MorU").equals("M")) {
                        ProductionFunction pf = pa.getProductionFunction();
                        if (pf instanceof ExponentialQuantities) {
                            ExponentialQuantities exq =
                                    (ExponentialQuantities) pf;
                            exq.addCommodity(new ExponentialQuantities.Quantity(c,
                                    makeUse.getValueAt(muRow, "Minimum"),
                                    makeUse.getValueAt(muRow,
                                            "OutsideExpMultiplier"),
                                    makeUse.getValueAt(muRow, "UtilityMultiplier"),
                                    makeUse.getValueAt(muRow, "UtilityOffset"),
                                    makeUse.getValueAt(muRow,
                                            "ImpactOnOverallUtility")));
                        } else if (pf instanceof LogitSubstitution) {
                            LogitSubstitution ls = (LogitSubstitution) pf;
                            ls.addCommodity(new LogitSubstitution.Quantity(c,
                                    makeUse.getValueAt(muRow, "Minimum"),
                                    makeUse.getValueAt(muRow, "Discretionary"),
                                    makeUse.getValueAt(muRow, "UtilityScale"),
                                    makeUse.getValueAt(muRow, "UtilityOffset")));
                        } else {
                            throw new RuntimeException("Don't know how to read in production function of type"
                                    + pf.getClass());
                        }
                    } else
                        throw new RuntimeException("MorU column in MakeUse.txt must be either 'M' or 'U'");
                }
            }
            if (!found)
                throw new RuntimeException("Incorrect AggregateActivity name in MakeUseI.csv:"+activityName);
        }
        Iterator pit = ProductionActivity.getAllProductionActivities().iterator();
        while (pit.hasNext()) {
            AggregateActivity aa = (AggregateActivity) pit.next();
            aa.getConsumptionFunction().sortToMatch(Commodity.getAllCommodities());
            aa.getProductionFunction().sortToMatch(Commodity.getAllCommodities());
        }

    }

    public void doProjectSpecificInputProcessing() {
    
    }


    protected TableDataSet loadTableDataSet(String tableName, String source) {
        boolean useSQLInputs=false;
        String useSqlInputsString = ResourceUtil.getProperty(rb, "pi.useSQLInputs");
        if (useSqlInputsString != null) {
            if (useSqlInputsString.equalsIgnoreCase("true")) {
                useSQLInputs = true;
            }
        }

        String inputPath = ResourceUtil.getProperty(rb, source);
        if(inputPath == null){
            logger.warning("Property '" + source + "' could not be found in ResourceBundle");
            return null;
        }
        TableDataSet table = null;
        String fileName = null;
        try {
            if (useSQLInputs) {
                table = getJDBCTableReader().readTable(tableName);
            } else {
                fileName = inputPath +tableName + ".csv";
                CSVFileReader reader = new CSVFileReader();
                table = reader.readFile(new File(fileName));
            }
        } catch (IOException e) {
            logger.severe("Can't find input table " + fileName);
        }
        return table;
    }

    protected JDBCTableReader getJDBCTableReader() {
        JDBCTableReader jdbcTableReader = new JDBCTableReader(getJDBCConnection());
        String excelInputs =
                ResourceUtil.getProperty(rb, "pi.excelInputs");
        if (excelInputs != null) {
            if (excelInputs.equalsIgnoreCase("true")) {
                jdbcTableReader.setMangleTableNamesForExcel(true);
            }
        }

        return jdbcTableReader;
    }

    private JDBCConnection getJDBCConnection() {
        String datasourceName =
                ResourceUtil.getProperty(rb, "pi.datasource");
        String jdbcDriver =
                ResourceUtil.getProperty(rb, "pi.jdbcDriver");
//                Class.forName(jdbcDriver);
        JDBCConnection jdbcConnection = new JDBCConnection(datasourceName, jdbcDriver, "", "");
        return jdbcConnection;
    }

    /**
     *
     */
    protected void recalcActivitySizeTerms() {
        logger.info("Reading Activity Size Terms");
        TableDataSet activitySizeTermsCalculation = loadTableDataSet("ActivitySizeTermsI","pi.base.data");
        if (activitySizeTermsCalculation == null) {
            logger.warning("No ActivitySizeTermsI table, not recalculating activity size terms from floorspace quantities");
            return;
        }
        Set zeroedOutActivities = new HashSet();
        for (int row=1;row<= activitySizeTermsCalculation.getRowCount();row++) {
            String activityName = activitySizeTermsCalculation.getStringValueAt(row,"Activity");
            ProductionActivity a = ProductionActivity.retrieveProductionActivity(activityName);
            if (a==null) {
                logger.severe("Bad production activity in zone constant calculation "+activityName);
                throw new Error("Bad production activity in zone constant calculation "+activityName);
            }
            if (!zeroedOutActivities.contains(a)) {
                zeroedOutActivities.add(a);
                a.setSizeTermsToZero();
            }
            double weight = activitySizeTermsCalculation.getValueAt(row,"Weight");
            String commodityName = activitySizeTermsCalculation.getStringValueAt(row,"Floorspace");
            Commodity c = Commodity.retrieveCommodity(commodityName);
            if (c==null)  {
                logger.severe("Bad commodity name in zone constant calculation "+commodityName);
                throw new Error("Bad commodity name in zone constant calculation "+commodityName);
            }
            FloorspaceQuantityStorage fsi = (FloorspaceQuantityStorage) floorspaceInventory.get(commodityName);
            for (int r = 0; r< fsi.inventory.length; r++ ) {
                Integer betaZoneNumber = ((Integer) floorspaceZoneCrossref.get(new Integer(r)));
                if (betaZoneNumber != null) {  
                    // not all alphazone numbers are valid
                    a.increaseSizeTerm(betaZoneNumber.intValue(), weight*fsi.inventory[r]);
                }
            }
        }
    }

    /**
     * 
     */
    protected void recalcFloorspaceBuyingSizeTerms() {
        logger.info("Reading Floorspace Buying Size Terms");
        TableDataSet floorspaceSizeTermsCalculation = null;
        try {
            floorspaceSizeTermsCalculation = loadTableDataSet("FloorspaceBuyingSizeTermsI","pi.base.data");
        } catch (RuntimeException e) {
            logger.warning("Exception loading floorspace buying size terms "+e);
        }
        if (floorspaceSizeTermsCalculation == null) {
            logger.warning("No FloorspaceBuyingSizeTermsI table, not recalculating floorspace buying size terms from floorspace quantities");
            return;
        }
        Hashtable floorspaceGroups = new Hashtable();
        for (int row=1;row<= floorspaceSizeTermsCalculation.getRowCount();row++) {
            String groupName = floorspaceSizeTermsCalculation.getStringValueAt(row,"FloorspaceGroup");
            if (!floorspaceGroups.containsKey(groupName)) {
                FloorspaceQuantityStorage inv = new FloorspaceQuantityStorage(groupName, maxAlphaZone+1);
                floorspaceGroups.put(groupName,inv);
            }
        }
        for (int row=1;row<= floorspaceSizeTermsCalculation.getRowCount();row++) {
            String groupName = floorspaceSizeTermsCalculation.getStringValueAt(row,"FloorspaceGroup");
            FloorspaceQuantityStorage group = (FloorspaceQuantityStorage) floorspaceGroups.get(groupName);
            String typeName = floorspaceSizeTermsCalculation.getStringValueAt(row,"FloorspaceType");
            FloorspaceQuantityStorage type = (FloorspaceQuantityStorage) floorspaceInventory.get(typeName);
            for (int z=0;z<type.inventory.length;z++) {
                group.inventory[z] += type.inventory[z];
            }
        }
        for (int row=1;row<= floorspaceSizeTermsCalculation.getRowCount();row++) {
            String groupName = floorspaceSizeTermsCalculation.getStringValueAt(row,"FloorspaceGroup");
            FloorspaceQuantityStorage group = null;
            group = (FloorspaceQuantityStorage) floorspaceGroups.get(groupName);
            String typeName = floorspaceSizeTermsCalculation.getStringValueAt(row,"FloorspaceType");
            FloorspaceQuantityStorage type = (FloorspaceQuantityStorage) floorspaceInventory.get(typeName);
            for (int betaZone=0;betaZone < zones.length; betaZone++) {
                int betaZoneUserNumber = zones[betaZone].getZoneUserNumber();
                double groupQuantity = 0;
                double typeQuantity = 0;
                for (int alphaZone =0;alphaZone<type.inventory.length;alphaZone++) {
                    Integer a = new Integer(alphaZone);
                    Integer b = (Integer) floorspaceZoneCrossref.get(a);
                    if (b!=null) {
                        if (b.intValue()==betaZoneUserNumber) {
                            groupQuantity += group.inventory[alphaZone];
                            typeQuantity += type.inventory[alphaZone]; 
                        }
                    }
                }
                double size = typeQuantity;
                if (!(groupName.equalsIgnoreCase("none") || groupName.equals(""))) {
                    size = typeQuantity/groupQuantity;
                }
                Commodity c = type.getFloorspaceType();
                Exchange x = c.getExchange(betaZone);
                x.setBuyingSizeTerm(size);
            }
        }
       }

    /**
     * 
     */
    protected void recalcFloorspaceImport() {
        logger.info("Getting Floorspace Params from Property file");
        String deltaString = ResourceUtil.getProperty(rb,"pi.floorspaceDelta");
        if (deltaString ==null) {
            logger.warning("No pi.floorspaceDelta entry in properties file -- not calculating floorspace import from floorspace inventory");
            return;
        }
        double delta=Double.valueOf(deltaString).doubleValue();
        double eta = Double.valueOf(ResourceUtil.getProperty(rb,"pi.floorspaceEta")).doubleValue();
        double p0 = Double.valueOf(ResourceUtil.getProperty(rb,"pi.floorspaceP0")).doubleValue();
        double slope = Double.valueOf(ResourceUtil.getProperty(rb,"pi.floorspaceSlope")).doubleValue();
        double midpoint = Double.valueOf(ResourceUtil.getProperty(rb,"pi.floorspaceMidpoint")).doubleValue();
        
        Iterator it = floorspaceInventory.values().iterator();
        while(it.hasNext()) {
            FloorspaceQuantityStorage fqs = (FloorspaceQuantityStorage) it.next();
            for (int bz = 0; bz<zones.length; bz++) {
                AbstractTAZ betaZone = zones[bz];
                int betaZoneNumber = betaZone.getZoneUserNumber();
                double quantityExpected = 0;
                for (int az=0; az< fqs.inventory.length; az++)  {
                    Integer betaZoneInteger = (Integer) floorspaceZoneCrossref.get(new Integer(az));
                    if (betaZoneInteger != null) {
                        if (betaZoneInteger.intValue() == betaZoneNumber) {
                            quantityExpected += fqs.inventory[az];
                        }
                    }
                }
                Commodity c = fqs.getFloorspaceType();
                Exchange x = c.getExchange(bz);
                double priceExpected = c.getExpectedPrice();
                x.setImportFunction(new LogisticPlusLinearFunction(quantityExpected*midpoint,
                        priceExpected*p0,
                        eta/priceExpected,
                        quantityExpected*delta,
                        quantityExpected*slope/priceExpected));
                
            }
           }
        
    }



    public void writeExchangeResults() {
        try {
            BufferedWriter exchangeResults = new BufferedWriter(new FileWriter(getOutputPath() + "ExchangeResults.csv"));
            exchangeResults.write("Commodity,ZoneNumber,BoughtFrom,SoldTo,Surplus,Imports,Exports,Price\n");
            Iterator it = Commodity.getAllCommodities().iterator();
            while (it.hasNext()) {
                Commodity c = (Commodity) it.next();
                Iterator exchangeIterator = c.getAllExchanges().iterator();
                while (exchangeIterator.hasNext()) {
                    Exchange ex = (Exchange) exchangeIterator.next();
                    try {
                        exchangeResults.write(ex.myCommodity.name + "," + ex.exchangeLocationUserID + ",");
                        double[] importExport = ex.importsAndExports(ex.getPrice());
                        exchangeResults.write(ex.boughtTotal() + ","+ ex.soldTotal()+","+ ex.exchangeSurplus() + "," + importExport[0] + "," + importExport[1] + "," + ex.getPrice() + "\n");
                    } catch (IOException e) {
                     System.err.println("Error adding exchange " + this + " to table");
                     e.printStackTrace();
                    }
    
                }
            }
            logger.info("ExchangeResults.csv has been written");
            exchangeResults.close();
        } catch (IOException e) {
            logger.severe("Can't create exchange results output file");
            e.printStackTrace();
        }
    }

    public void writeExchangeResults(String commodityName) {
        Commodity c = Commodity.retrieveCommodity(commodityName);
        try {
            BufferedWriter exchangeResults = new BufferedWriter(new FileWriter(getOutputPath() + commodityName+"_ExchangeResults.csv"));
            exchangeResults.write("Commodity,ZoneNumber,BoughtFrom,SoldTo,Surplus,Imports,Exports,Price\n");
            Iterator exchangeIterator = c.getAllExchanges().iterator();
            while (exchangeIterator.hasNext()) {
                Exchange ex = (Exchange) exchangeIterator.next();
                try {
                    exchangeResults.write(ex.myCommodity.name + "," + ex.exchangeLocationUserID + ",");
                    double[] importExport = ex.importsAndExports(ex.getPrice());
                    exchangeResults.write(ex.boughtTotal() + ","+ ex.soldTotal()+","+ ex.exchangeSurplus() + "," + importExport[0] + "," + importExport[1] + "," + ex.getPrice() + "\n");
                } catch (IOException e) {
                    System.err.println("Error adding exchange " + this + " to table");
                    e.printStackTrace();
                }
                
            }
            exchangeResults.close();
        } catch (IOException e) {
            logger.severe("Can't create exchange results output file for commodity "+commodityName);
            e.printStackTrace();
        }
    }
    



    public void writeFlowsToFile(BufferedWriter writer, CommodityFlowArray myFlows){
            Commodity com = myFlows.theCommodityZUtility.getCommodity();
            int tazIndex = myFlows.theCommodityZUtility.myTaz.getZoneIndex();
            int tazUserID = myFlows.theCommodityZUtility.myTaz.getZoneUserNumber();
            char selling = 's';
            if (myFlows.theCommodityZUtility instanceof BuyingZUtility) selling = 'b';
            try {
                if (com.exchangeType == 'p' && selling == 's' || com.exchangeType == 'c' && selling == 'b' || com.exchangeType == 'n') {
                    writer.write("\"" + com.toString() + "\",");
                    Exchange x = com.getExchange(tazIndex);
                    writer.write(tazUserID + ",");
                    writer.write(tazUserID + ",");
                    if (selling == 'b') {
                        writer.write(String.valueOf(-x.getFlowQuantity(tazIndex, selling)));
                    } else {
                        writer.write(String.valueOf(x.getFlowQuantity(tazIndex, selling)));
                    }
    /*                if (myFlows.timeAndDistanceUtilities && myFlows.peakAutoSkims != null) {
                        writer.write("," + myFlows.peakAutoSkims.getDistance(tazUserID, tazUserID));
                        writer.write("," + myFlows.peakAutoSkims.getTime(tazUserID, tazUserID));
                    } */
                    writer.write("\n");
                } else {
                    Collection theExchanges = com.getAllExchanges();
                    synchronized (theExchanges) {
                        Iterator it = theExchanges.iterator();
                        while (it.hasNext()) {
                            Exchange x = (Exchange) it.next();
                            int exchangeID = x.exchangeLocationUserID;
                            writer.write("\"" + com.toString() + "\",");
                            if (selling == 'b') {
                                writer.write(exchangeID + ",");
                                writer.write(tazUserID + ",");
                                writer.write(String.valueOf(-x.getFlowQuantity(tazIndex, selling)));
    /*                            if (myFlows.timeAndDistanceUtilities && myFlows.peakAutoSkims != null) {
                                    writer.write("," + myFlows.peakAutoSkims.getDistance(exchangeID, tazUserID));
                                    writer.write("," + myFlows.peakAutoSkims.getTime(exchangeID, tazUserID));
                                } */
                                writer.write("\n");
                            } else {
                                writer.write(tazUserID + ",");
                                writer.write(exchangeID + ",");
                                writer.write(String.valueOf(x.getFlowQuantity(tazIndex, selling)));
    /*                            if (myFlows.timeAndDistanceUtilities && myFlows.peakAutoSkims != null) {
                                    writer.write("," + myFlows.peakAutoSkims.getDistance(tazUserID, exchangeID));
                                    writer.write("," + myFlows.peakAutoSkims.getTime(tazUserID, exchangeID));
                                } */
                                writer.write("\n");
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


    public void writeFlowHistograms(String name) {
        Commodity com = Commodity.retrieveCommodity(name);
        Matrix b = com.getBuyingFlowMatrix();
        Matrix s = com.getSellingFlowMatrix();
        writeFlowHistograms(name,b,s);
    }
    
    public void writeFlowZipMatrices(String name) {
        Commodity com = Commodity.retrieveCommodity(name);
        ZipMatrixWriter  zmw = new ZipMatrixWriter(new File(getOutputPath()+"buying_"+com.name+".zipMatrix"));
        Matrix b = com.getBuyingFlowMatrix();
        zmw.writeMatrix(b);
        zmw = new ZipMatrixWriter(new File(getOutputPath()+"selling_"+com.name+".zipMatrix"));
        Matrix s = com.getSellingFlowMatrix();
        zmw.writeMatrix(s);
        logger.info("Buying and Selling Commodity Flow Matrices have been written for " + name);
        writeFlowHistograms(name,b,s);
    }
    
    private void writeFlowHistograms(String commodityName, Matrix buyingMatrix, Matrix sellingMatrix) {

        Iterator it = histogramSpecifications.iterator();
        BufferedWriter histogramFile = null;
        while (it.hasNext()) {
            HistogramSpec hspec = (HistogramSpec) it.next();
            if (hspec.commodityName.equals(commodityName)) {
                double[] boundaries = new double[hspec.boundaries.size()];
                for (int bound=0;bound<hspec.boundaries.size();bound++) {
                    boundaries[bound] = (double) ((Float) hspec.boundaries.get(bound)).doubleValue();
                }
                MatrixHistogram mhBuying = new MatrixHistogram(boundaries);
                MatrixHistogram mhSelling = new MatrixHistogram(boundaries);
                Matrix skim=null;
                try {
                    skim =((SomeSkims) TransportKnowledge.globalTransportKnowledge).getMatrix(hspec.categorizationSkim);
                } catch (RuntimeException e) {
                    logger.warning("Can't find skim name "+hspec.categorizationSkim+" in existing skims -- attempting to read it separately");
                }
                if (skim==null) {
                    // try to read it in.
                    ((SomeSkims) TransportKnowledge.globalTransportKnowledge).addZipMatrix(hspec.categorizationSkim);
                    skim =((SomeSkims) TransportKnowledge.globalTransportKnowledge).getMatrix(hspec.categorizationSkim);
                }
                mhBuying.generateHistogram(skim,buyingMatrix);
                mhSelling.generateHistogram(skim,sellingMatrix);
                try {
                    if (histogramFile == null) histogramFile = new BufferedWriter(new FileWriter(getOutputPath() + "histograms_"+commodityName+".csv"));
                    mhBuying.writeHistogram("buying", histogramFile);
                    mhSelling.writeHistogram("selling", histogramFile);
                } catch (IOException e) {
                    logger.warning("IO exception "+e+" in writing out histogram file for "+this);
                    e.printStackTrace();
                }
            }
        }
        if (histogramFile !=null) {
            try {
                histogramFile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
    
    public void writeAllFlowZipMatrices() {
        Iterator com = Commodity.getAllCommodities().iterator();
        while (com.hasNext()) {
            writeFlowZipMatrices(((Commodity)com.next()).getName());
        }
    }

    public void writeAllHistograms() {
        Iterator com = Commodity.getAllCommodities().iterator();
        while (com.hasNext()) {
            writeFlowHistograms(((Commodity)com.next()).getName());
        }
    }

    
    
    public void writeFlowTextFiles() {
        try {
            BufferedWriter consumptionFlows = new BufferedWriter(new FileWriter(getOutputPath() + "FlowsFromConsumption(buying).csv"));
            BufferedWriter productionFlows = new BufferedWriter(new FileWriter(getOutputPath() + "FlowsFromProduction(selling).csv"));
            consumptionFlows.write("commodity,origin,destination,quantity,distance,time\n");
            productionFlows.write("commodity,origin,destination,quantity,distance,time\n");
            //AbstractTAZ[] zones = AbstractTAZ.getAllZones();
            //for (int z = 0; z < zones.length; z++) {
            Iterator cit = Commodity.getAllCommodities().iterator();
            while (cit.hasNext()) {
                Commodity c = (Commodity) cit.next();
                for (int bs = 0; bs < 2; bs++) {
                    Hashtable ht;
                    if (bs == 0)
                        ht = c.buyingTazZUtilities;
                    else
                        ht = c.sellingTazZUtilities;
                    Iterator it = ht.values().iterator();
                    while (it.hasNext()) {
                        CommodityZUtility czu = (CommodityZUtility) it.next();
                        if (bs == 0)
                            writeFlowsToFile(consumptionFlows,czu.getMyFlows());
                        else
                            writeFlowsToFile(productionFlows,czu.getMyFlows());
                    }
                }
            }
            consumptionFlows.close();
            productionFlows.close();
        } catch (IOException e) {
            logger.severe("Error writing flow tables to disk");
            e.printStackTrace();
        }
    }


    public void writeLocationTables() {
        logger.info("Writing Location Tables ");
        writeLocationTable();
        if (ResourceUtil.getProperty(rb,"pi.useFloorspaceZones").equalsIgnoreCase("true")) {
            writeFloorspaceZoneLocationTable();
        }
            

    }
    public void writeActivitySummary() {
        BufferedWriter activityFile;
        try {
            activityFile = new BufferedWriter(new FileWriter(getOutputPath() + "ActivitySummary.csv"));
            //TODO shall we write out the dollars spent and earned as well as the utility associated with size/variation/transport components?
            activityFile.write("Activity,CompositeUtility\n");
            Iterator it = ProductionActivity.getAllProductionActivities().iterator();
            while (it.hasNext()) {
                ProductionActivity p = (ProductionActivity)it.next();
                try {
                    activityFile.write(p.name+",");
                    activityFile.write(p.getUtility()+"\n");
                } catch (IOException e) {
                    System.err.println("Error adding activity quantity to activity location table");
                    e.printStackTrace();
                } catch (OverflowException e) {
                    activityFile.write("Overflow\n");
                }
            }
            logger.info("ActivitySummary.csv has been written");
            activityFile.close();
        } catch (IOException e) {
            logger.severe("Can't create location output file");
            e.printStackTrace();
        }
    }

    private void writeLocationTable() {

        BufferedWriter locationsFile;
        try {
            locationsFile = new BufferedWriter(new FileWriter(getOutputPath() + "ActivityLocations.csv"));
            //TODO shall we write out the dollars spent and earned as well as the utility associated with size/variation/transport components?
            boolean writeComponents = (ResourceUtil.getProperty(rb, "pi.writeUtilityComponents").equalsIgnoreCase("true"));
            locationsFile.write("Activity,ZoneNumber,Quantity,ProductionUtility,ConsumptionUtility,SizeUtility,LocationSpecificUtility,LocationUtility\n");
            Iterator it = ProductionActivity.getAllProductionActivities().iterator();
            while (it.hasNext()) {
                ProductionActivity p = (ProductionActivity)it.next();
                for (int z = 0; z < p.getMyDistribution().length; z++) {
                    try {
                        locationsFile.write(p.name+",");
                        locationsFile.write(p.getMyDistribution()[z].getMyTaz().getZoneUserNumber()+",");
                        locationsFile.write(p.getMyDistribution()[z].getQuantity()+",");
                        p.getMyDistribution()[z].updateLocationUtilityTerms(locationsFile);
                    } catch (IOException e) {
                        System.err.println("Error adding activity quantity to activity location table");
                        e.printStackTrace();
                    }
                }
            }
            logger.info("\tActivityLocations.csv has been written");
            locationsFile.close();
        } catch (IOException e) {
            logger.severe("Can't create location output file");
            e.printStackTrace();
        }
    }

    public void writeZonalMakeUseCoefficients() {
        BufferedWriter zMakeUseFile;
        try {
            zMakeUseFile = new BufferedWriter(new FileWriter(getOutputPath() + "ZonalMakeUse.csv"));
            zMakeUseFile.write("Activity,ZoneNumber,Commodity,MorU,Coefficient,Utility,Amount\n");
            Iterator it = ProductionActivity.getAllProductionActivities().iterator();
            while (it.hasNext()) {
                ProductionActivity p = (ProductionActivity)it.next();
                ConsumptionFunction cf = p.getConsumptionFunction();
                ProductionFunction pf = p.getProductionFunction();
                for (int z = 0; z < p.getMyDistribution().length; z++) {
                    double[] buyingZUtilities = new double[cf.size()];
                    for (int c = 0; c < cf.size(); c++) {
                        AbstractCommodity com = cf.commodityAt(c);
                        if (com == null) {
                            buyingZUtilities[c] = 0;
                        } else {
                            buyingZUtilities[c] = com.calcZUtility(zones[z], false);
                        }
                    } //CUSellc,z and CUBuyc,z have now been calculated for the commodites made or used by the activity
                    double[] consumptionAmounts = cf.calcAmounts(buyingZUtilities);
                    double[] sellingZUtilities = new double[pf.size()];
                    for (int c = 0; c < pf.size(); c++) {
                        AbstractCommodity com = pf.commodityAt(c);
                        if (com == null) {
                            sellingZUtilities[c] = 0;
                        } else {
                            sellingZUtilities[c] = com.calcZUtility(zones[z], true);
                        }
                    } //CUSellc,z and CUBuyc,z have now been calculated for the commodites made or used by the activity
                    double[] productionAmounts = pf.calcAmounts(sellingZUtilities);
                    double activityAmount = p.getMyDistribution()[z].getQuantity();
                    for (int c = 0; c < cf.size(); c++) {
                        AbstractCommodity com = cf.commodityAt(c);
                        if (com!= null) {
                            zMakeUseFile.write(p.name+",");
                            zMakeUseFile.write(zones[z].getZoneUserNumber()+",");
                            zMakeUseFile.write(com.getName()+",");
                            zMakeUseFile.write("U,");
                            zMakeUseFile.write(consumptionAmounts[c]+",");
                            zMakeUseFile.write(buyingZUtilities[c]+",");
                            zMakeUseFile.write(activityAmount*consumptionAmounts[c]+"\n");
                        }
                    } 
                    for (int c = 0; c < pf.size(); c++) {
                        AbstractCommodity com = pf.commodityAt(c);
                        if (com!= null) {
                            zMakeUseFile.write(p.name+",");
                            zMakeUseFile.write(zones[z].getZoneUserNumber()+",");
                            zMakeUseFile.write(com.getName()+",");
                            zMakeUseFile.write("M,");
                            zMakeUseFile.write(productionAmounts[c]+",");
                            zMakeUseFile.write(sellingZUtilities[c]+",");
                            zMakeUseFile.write(activityAmount*productionAmounts[c]+"\n");
                           }
                    }
                } // end of zone loop
            } // end of production activity loop
            logger.info("ZonalMakeUse.csv has been written");
            zMakeUseFile.close();
        } catch (Exception e) {
            logger.severe("Can't create ZonalMakeUse output file");
            e.printStackTrace();
        }
    }

    private void writeFloorspaceZoneLocationTable() {
        BufferedWriter locationsFile;
        try {
            locationsFile = new BufferedWriter(new FileWriter(getOutputPath() + "ActivityLocations2.csv"));
            locationsFile.write("Activity,ZoneNumber,Quantity\n");
            Iterator it = ProductionActivity.getAllProductionActivities().iterator();
            while (it.hasNext()) {
                ProductionActivity p = (ProductionActivity)it.next();
                ConsumptionFunction cf = p.getConsumptionFunction();
                double[] activityLocationsSplit = new double[maxAlphaZone()+1];
                for (int z = 0; z < p.getMyDistribution().length; z++) {
                    double pecasZoneTotal = p.getMyDistribution()[z].getQuantity();
                    double[] buyingZUtilities = new double[cf.size()];
                    try {
                        for (int c = 0; c < cf.size(); c++) {
                            AbstractCommodity com = cf.commodityAt(c);
                            if (com == null) {
                                buyingZUtilities[c] = 0;
                            } else {
                                buyingZUtilities[c] = cf.commodityAt(c).calcZUtility(zones[z], false);
                            }
                        } //CUSellc,z and CUBuyc,z have now been calculated for the commodites made or used by the activity
                        double[] amounts = cf.calcAmounts(buyingZUtilities);
                        double totalFloorspaceConsumption = 0;
                        for (int c =0; c<amounts.length;c++) {
                            Commodity commodity = (Commodity) cf.commodityAt(c);
                            if (commodity!=null) {
                                if (commodity.isFloorspaceCommodity()) {
                                    totalFloorspaceConsumption += amounts[c]; 
                                }
                            }
                        }
                        for (int c =0; c<amounts.length;c++) {
                            Commodity commodity = (Commodity) cf.commodityAt(c);
                            if (commodity!=null) {
                                if (commodity.isFloorspaceCommodity()) {
                                    double floorspaceTotal = 0;
                                    double activityAmountForCommodity=pecasZoneTotal*amounts[c]/totalFloorspaceConsumption;
                                    FloorspaceQuantityStorage fsi = (FloorspaceQuantityStorage) floorspaceInventory.get(cf.commodityAt(c).getName());
                                    for (int r = 0; r< fsi.inventory.length; r++ ) {
                                        Integer betaZoneNumber = ((Integer) floorspaceZoneCrossref.get(new Integer(r)));
                                        if (betaZoneNumber != null) {
                                            if (zones[z].zoneUserNumber== betaZoneNumber.intValue()) {
                                                floorspaceTotal += fsi.inventory[r];
                                            }
                                        }
                                    }
                                    if (floorspaceTotal==0) floorspaceTotal = 1; // prevent NaN's.
                                    for (int r = 0; r< fsi.inventory.length; r++ ) {
                                        Integer betaZoneNumber = ((Integer) floorspaceZoneCrossref.get(new Integer(r)));
                                        if (betaZoneNumber != null) {
                                            if (zones[z].zoneUserNumber== betaZoneNumber.intValue()) {
                                                activityLocationsSplit[r]+=activityAmountForCommodity*fsi.inventory[r]/floorspaceTotal;
                                            }
                                        }
                                    } //end for for writing alpha zones
                                    
                                } // endif for checking floorspace commodity type
                            } //endif for checking to see if commodity is null
                        }//endfor for iterating through commodities
                    } catch (Exception e) {
                        System.err.println("Error adding activity quantity to activity location table");
                        e.printStackTrace();
                    }
                } //end betazone loop
                for(int azone = 0; azone < activityLocationsSplit.length;azone++) {
                    if (floorspaceZoneCrossref.get(new Integer(azone))!=null) {
                        locationsFile.write(p.name+",");
                        locationsFile.write(azone+",");
                        locationsFile.write(activityLocationsSplit[azone]+"\n");
                    }
                }
            } // end production activity loop
            logger.info("\tActivityLocations2.csv has been written");
            locationsFile.close();
        } catch (IOException e) {
            logger.severe("Can't create location output file");
            e.printStackTrace();
        }
    }
    
    public void writeZUtilitiesTable() {
            BufferedWriter os = null;
            try {
                os = new BufferedWriter(new FileWriter(getOutputPath() + "CommodityZUtilities.csv"));
                boolean writeComponents = (ResourceUtil.getProperty(rb, "pi.writeUtilityComponents").equalsIgnoreCase("true"));
                if (writeComponents) {
                    os.write("Commodity,Zone,BuyingOrSelling,Quantity,zUtility,VariationComponent,PriceComponent,SizeComponent,TransportComponent1,TransportComponent2,TransportComponent3,TransportComponent4\n");
                } else {
                    os.write("Commodity,Zone,BuyingOrSelling,Quantity,zUtility\n");
                }
                Iterator it = Commodity.getAllCommodities().iterator();
                while(it.hasNext()) {
                    Commodity c = (Commodity) it.next();
                    for (int b =0; b<2; b++) {
                        Hashtable ht;
                        if (b==0) {
                            ht = c.buyingTazZUtilities;
                        } else {
                            ht = c.sellingTazZUtilities;
                        }
                        Iterator it2 = ht.values().iterator();
                        while (it2.hasNext()) {
                            CommodityZUtility czu = (CommodityZUtility) it2.next();
                            try {
                                os.write(czu.myCommodity.toString() + ",");
                                os.write(czu.myTaz.getZoneUserNumber() + ",");
                                if (czu instanceof BuyingZUtility) {
                                os.write("B,");
                            }
                            if (czu instanceof SellingZUtility) {
                                os.write("S,");
                            }
                            os.write(czu.getQuantity() + ",");
                            if (writeComponents) {
                                os.write(czu.getUtility(czu.getLastHigherLevelDispersionParameter()) + ",");
                                double[] components = czu.getUtilityComponents(czu.getLastHigherLevelDispersionParameter());
                                for (int i = 0;i<components.length-1;i++) {
                                    os.write(components[i]+",");
                                }
                                os.write(components[components.length-1]+"\n");
                            } else {
                                os.write(czu.getUtility(czu.getLastHigherLevelDispersionParameter()) + "\n");
                            }
                            } catch (IOException e) {
                                logger.fine("unable to write zUtility to file");
                            } catch (ChoiceModelOverflowException e) {
                                logger.warning("Overflow exception in calculating utility for CommodityZUtilityoutput " + this);
                            }
                        }
                    }
                }
                logger.info("CommodityZUtilities.csv has been written");
                os.close();
            } catch (IOException e) {
                logger.severe("Can't create CommodityZUtilities output file");
                e.printStackTrace();
            }
        }

    /**
     * @return Returns the timePeriod.
     */
    public int getTimePeriod() {
        return timePeriod;
    }

    /**
     * @param timePeriod The timePeriod to set.
     */
    public void setTimePeriod(int timePeriod) {
        this.timePeriod = timePeriod;
    }

    public void setResourceBundle(ResourceBundle rb){
        this.rb = rb;
    }

    /**
     * 
     */
    public void writeOutputs() {
        logger.info("Writing ActivityLocations.csv and ActivityLocations2.csv");
        writeLocationTables();// writes out ActivityLocations.csv and ActivityLocations2.csv
        logger.info("Writing ZonalMakeUse.csv");
        writeZonalMakeUseCoefficients(); //writes out ZonalMakeUse.csv
        logger.info("Writing CommodityZUtilities.csv");
        writeZUtilitiesTable(); //writes out CommodityZUtilities.csv
        logger.info("Writing ExchangeResults.csv");
        writeExchangeResults(); //write out ExchangeResults.csv (prices of all commodites at all exchanges)
        logger.info("Writing ActivitySummary.csv");
        writeActivitySummary(); // write out the top level logsums for benefit analysis - ActivitySummary.csv
        logger.info("Writing buying_$commodityName.csv and selling_$commodityName.csv");
        readInHistogramSpecifications();
        if (getWriteZipMatrices()) {
        writeAllFlowZipMatrices(); //writes out a 'buying_commodityName.csv' and a 'selling_commodityName.csv'
                                  //files for each commodity
        } else {
            writeAllHistograms();
        }
        
    }

    private void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    private String getOutputPath() {
        if (outputPath == null) {
            outputPath = ResourceUtil.getProperty(rb, "output.data");
        }
        return outputPath;
    }

    private boolean getWriteZipMatrices() {
        String writem = ResourceUtil.getProperty(rb, "pi.writeFlowMatrices");
        if (writem==null) return true;
        if (writem.length()==0) return true;
        if (writem.equalsIgnoreCase("false")) return false;
        return true;
    }

   }
