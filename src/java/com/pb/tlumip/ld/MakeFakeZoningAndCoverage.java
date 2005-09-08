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
/* Generated by Together */

package com.pb.osmp.ld;

import com.borland.dx.dataset.TableDataSet;
import com.pb.common.datastore.DataManager;
import com.pb.common.grid.GridManager;
import com.pb.common.util.ResourceUtil;

import java.util.ArrayList;
import java.util.Random;
import java.util.ResourceBundle;

public class MakeFakeZoningAndCoverage {
   public static void main(String[] args) {
        ResourceBundle rbld = ResourceUtil.getResourceBundle( "ld" );
        gridPath = ResourceUtil.getProperty(rbld, "grid.path");
//        ResourceBundle rbha = ResourceUtil.getResourceBundle( "ha" );
//        String spaceTypePath = ResourceUtil.getProperty(rbha, "spaceType.path");
//        DevelopmentTypeInterface dtypes[] = setUpDevelopmentTypes(spaceTypePath);

//        ZoningScheme.setUpZoningSchemes(reloadTableFromScratchFromTextFile(spaceTypePath,"ZoningSchemes"));
        
        // list of the fake zoning schemes get some zoning schemes
//        ZoningScheme residential = ZoningScheme.getAlreadyCreatedZoningScheme("R");
//        ZoningScheme commercial = ZoningScheme.getAlreadyCreatedZoningScheme("C");
//        ZoningScheme commercialIndustrial = ZoningScheme.getAlreadyCreatedZoningScheme("CI");
//        ZoningScheme permissive = ZoningScheme.getAlreadyCreatedZoningScheme("P");
//        ZoningScheme residentialLow = ZoningScheme.getAlreadyCreatedZoningScheme("RL");
//        ZoningScheme allowNone = ZoningScheme.getAlreadyCreatedZoningScheme("X");
        
        GridManager luCodes = new GridManager(gridPath+"LU2.grid","r");
        GridManager floorspace = new GridManager(gridPath+"floorspace.grid","rw");
        GridManager yrBuilt = new GridManager(gridPath+"yearBuilt.grid", "rw");
        GridManager zoning = new GridManager(gridPath+"zoning.grid", "rw");
        GridManager coverage = new GridManager(gridPath+"coverage.grid", "rw");
        Random random = new Random();
        for (int row = 0; row < luCodes.getNrows();row  ++) {
            int[] luCodeRow = luCodes.getRow(row);
            int[] yrBuiltRow = yrBuilt.getRow(row);
            int[] zoningRow = zoning.getRow(row);
            int[] coverageRow = coverage.getRow(row);
            int[] floorspaceRow = floorspace.getRow(row);
            for (int col=0;col<luCodeRow.length;col++ ) {
                switch (luCodeRow[col]) {
                    case 503:
                    case 22:
                    case 112:
                    case 504:
                        // residential high development and zoning
                        coverageRow[col] ='H';
                        yrBuiltRow[col] = random.nextInt(30)+65; // built between 1965 and 1995
                        floorspaceRow[col] = random.nextInt(2 * 9860); // Max FAR of 2
                        zoningRow[col] = 'H';
                        break;
                    case 21:
                    case 501:
                    case 110:
                    case 111:
                    case 502:
                        // residential low development and zoning
                        coverageRow[col] ='R';
                        yrBuiltRow[col] = random.nextInt(30) + 65;
                        floorspaceRow[col] = random.nextInt(9860/2); // Max FAR of 0.5
                        zoningRow[col] = 'R';
                        break;
                    case 23:
                    case 507:
                        // 1/2 industrial development, 1/2 commercial development, CommercialIndustrial zoning
                        if (random.nextInt(2) == 0) {
                            coverageRow[col] ='C';
                        } else {
                            coverageRow[col] ='I';
                        }
                        yrBuiltRow[col] = random.nextInt(30) + 65;
                        floorspaceRow[col] = random.nextInt(9860/2); // Max FAR of 0.5
                        zoningRow[col] = 'I';
                        break;
                    case 506:
                    case 128:
                    case 144:
                        // office development and commercial zoning
                        coverageRow[col] ='C';
                        yrBuiltRow[col] = random.nextInt(30) + 65;
                        floorspaceRow[col] = random.nextInt(9860/2); // Max FAR of 0.5
                        zoningRow[col] = 'C';
                        break;
                    case 508:
                    case 131:
                    case 130:
                    case 32: // quaries, stipmines, gravel pits
                        // industrial development and CommercialIndustrial zoning
                        coverageRow[col] ='I';
                        yrBuiltRow[col] = random.nextInt(30) + 65;
                        floorspaceRow[col] = random.nextInt(9860/2); // Max FAR of 0.5
                        zoningRow[col] = 'I';
                        break;
                    case 129:
                        // retail development and commercial zoning
                        coverageRow[col] ='S';
                        yrBuiltRow[col] = random.nextInt(30) + 65;
                        floorspaceRow[col] = random.nextInt(9860/2); // Max FAR of 0.5
                        zoningRow[col] = 'C';
                        break;
                    case 614:
                    case 141:
                        coverageRow[col] ='E';
                        yrBuiltRow[col] = random.nextInt(30) +65;
                        floorspaceRow[col] = random.nextInt(9860/2); // Max FAR of 0.5
                        zoningRow[col] = 'I';
                        // universities and schools, floor education and commercialIndustrial zoning
                    case 612:
                         // golf courses
                    case 685: // military
                    case 691: // DOE land
                    case 471: // Corps of Engineers lands
                    case 470: // Bureau of Reclamation lands
                         // nothing permitted zoning 
                        coverageRow[col] ='V';
                        yrBuiltRow[col] = 0;
                        floorspaceRow[col] = 0;
                        zoningRow[col] = 'X';
                         break;
                    case 140: // public facilities
                    case 143: // state/county property
                    case 615: // jails
                        coverageRow[col] ='G';
                        yrBuiltRow[col] = random.nextInt(30) +65;
                        floorspaceRow[col] = random.nextInt(9860/2); // Max FAR of 0.5
                        zoningRow[col] = 'C';
                        // gov support floorspace, commercial zoning
                        break;
                    case 51: // shrubland
                    case 481: // national forest
                    case 42: //evergreen forest
                    case 71: //grasslands
                    case 690: //commercial timberlands
                    case 43: // mixed forest
                    case 41: // deciduous forest
                    case 554: // various forest
                         // nothing permitted zoning, no floorspace
                        coverageRow[col] ='V';
                        yrBuiltRow[col] = 0;
                        floorspaceRow[col] = 0;
                        zoningRow[col] = 'X';
                         break;
                    case 81: // pasture, hay
                    case 83: // small grains
                    case 84: // fallow
                    case 82: // row crops
                    case 567: // grass seed
                    case 585: // various ag
                    case 583: // various ag
                    case 61: // various ag
                    case 592: //orchard
                    case 568: // irrigated annual rotation
                    case 582: // various ag
                    case 107: // various ag
                    case 571: // various ag
                    case 593: // christmas trees
                    case 580: // various ag
                    case 572: // various ag
                    case 590: // irrigated field crop
                    case 598: // bare/fallow
                    case 579: // various ag
                    case 516: // rural structures
                    case 573: // various ag
                    case 584: // various ag
                    case 581: // various ag
                    case 574: // various ag
                    case 576: // various ag
                    case 575: // various ag
                    case 595: // woodlot
                    case 578: // various ag
                    case 577: // various ag
                         // agricultural floorspace, ag zoning
                        coverageRow[col] ='A';
                        yrBuiltRow[col] = random.nextInt(30) +65;
                        floorspaceRow[col] = 9860; // FAR of exactly 1.
                        zoningRow[col] = 'I';
                         break;
                    case 689:
                    case 561:
                    case 560:
                    case 553:
                    case 587:
                    case 106:
                    case 558:
                    case 562:
                    case 559:
                    case 552:
                    case 557:
                    case 551:
                    case 586:
                    case 555:
                    case 409:
                    case 556:
                    case 566:
                    default:
                        coverageRow[col] ='V';
                        yrBuiltRow[col] = 0;
                        floorspaceRow[col] = 0;
                        zoningRow[col] = 'X';
                       // various forest cover and natural vegetation, no floorspace, nothing allowed
                       break;
                    case 521:
                    case 520:
                    case 800:
                    case 518:
                    case 519:
                    case 809:
                    case 810:
                        // transportation, no floorspace, nothing allowed
                        coverageRow[col] ='V';
                        yrBuiltRow[col] = 0;
                        floorspaceRow[col] = 0;
                        zoningRow[col] = 'X';
                        break;
                    case 701:
                    case 793:
                    case 11:
                    case 702:
                    case 700:
                    case 533:
                    case 532:
                        // water, no floorspace nothing allowed
                        coverageRow[col] ='V';
                        yrBuiltRow[col] = 0;
                        floorspaceRow[col] = 0;
                        zoningRow[col] = 'X';
                        break;
                    case 31:
                    case 92:
                    case 33:
                    case 524:
                    case 91:
                    case 511:
                    case 589:
                    case 591:
                    case 105:
                    case 539:
                    case 104:
                    case 12:
                    case 529:
                    case 100:
                    case 542:
                    case 540:
                       // undeveloped ... some may be developable but some are rock/perennial ice/snow etc.
                        coverageRow[col] ='V';
                        yrBuiltRow[col] = 0;
                        floorspaceRow[col] = 0;
                        zoningRow[col] = 'X';
                       break;
                    case 699:
                    case 85:
                    case 410:
                    case 613:
                        // protected lands
                        coverageRow[col] ='V';
                        yrBuiltRow[col] = 0;
                        floorspaceRow[col] = 0;
                        zoningRow[col] = 'X';
                        break;
                }
            } 
            zoning.putRow(zoningRow,row);
            yrBuilt.putRow(yrBuiltRow,row);
            floorspace.putRow(floorspaceRow,row);
            coverage.putRow(coverageRow,row);
            System.out.println("row "+row);
        }
        luCodes.close();
        floorspace.close();
        yrBuilt.close();
        zoning.close();
        coverage.close();
    }

    private static TableDataSet reloadTableFromScratchFromTextFile(String path, String tableName) {
        TableDataSet table = dm.getTableDataSet(tableName);
        try {
            table.empty();
        } catch (com.borland.dx.dataset.DataSetException e) { };
        DataManager.closeTable(table);
        dm.deleteTable(tableName);
        dm.loadTable(tableName, path + tableName, path + tableName);
        table = dm.getTableDataSet(tableName); //Add a table to data-store
        return table;
    }

    static public final DataManager dm = new DataManager();
    static private String gridPath;

    public static DevelopmentType[] setUpDevelopmentTypes(String spaceTypePath) {
        TableDataSet ctab = reloadTableFromScratchFromTextFile(spaceTypePath,"DevelopmentTypes");
        ArrayList dtypes = new ArrayList();
        try {
            while (ctab.inBounds()) {
                String typeName = (ctab.getString("DevelopmentTypeName"));
                int gridCode = ctab.getString("GridCode").charAt(0);
                boolean dynamic = ctab.getBoolean("DynamicPricesDevelopmentType");
                if (dynamic) {
                    dtypes.add(new DynamicPricesDevelopmentType(typeName,
                        ctab.getDouble("PortionVacantMultiplier"),
                        ctab.getDouble("EquilibriumVacancyRate"),
                        ctab.getDouble("MinimumBasePrice"), gridCode));
                } else {
                    dtypes.add(new DevelopmentType(typeName,gridCode));
                }
                ctab.next();
            }
        } catch (com.borland.dx.dataset.DataSetException e) {
            System.err.println("Error: setUpDevelopmentTypes()");
            e.printStackTrace();
        }
        DevelopmentType[] d = new DevelopmentType[dtypes.size()];
        return (DevelopmentType[]) dtypes.toArray(d);
    }



}

