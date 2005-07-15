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
package com.pb.tlumip.pt.tourmodes;
import com.pb.tlumip.model.Mode;
import com.pb.tlumip.model.ModeType;
import com.pb.tlumip.model.TravelTimeAndCost;
import com.pb.tlumip.pt.PersonTourModeAttributes;
import com.pb.tlumip.pt.TourModeParameters;
import com.pb.tlumip.pt.ZoneAttributes;

import org.apache.log4j.Logger;

/**  
 * Walk mode
 * 
 * @author Joel Freedman
 * @version 1.0 12/01/2003
 * 
 */

public class Walk extends Mode {
    final static Logger logger = Logger.getLogger("com.pb.tlumip.pt.default");
     
 //    public boolean isAvailable=true;
 //    public boolean hasUtility=false;
     
 //    double utility=0;

     public Walk(){
         isAvailable = true;
                hasUtility = false;
                utility = 0.0D;
          alternativeName=new String("Walk");
          type=ModeType.WALK;
     }

    /** Calculates utility of walk mode
     * 
     * @param inbound - In-bound TravelTimeAndCost
     * @param outbound - Outbound TravelTimeAndCost
     * @param z - ZoneAttributes (Currently only parking cost)
     * @param c - TourModeParameters
     * @param p - PersonTourModeAttributes
     */
    
     public void calcUtility(TravelTimeAndCost inbound, TravelTimeAndCost outbound,
          ZoneAttributes z,TourModeParameters c, PersonTourModeAttributes p){
               
          if(inbound.walkDistance>4) isAvailable=false;
          if(outbound.walkDistance>4) isAvailable=false;

          if(isAvailable){
               time=(inbound.walkTime+outbound.walkTime);
               utility=(
                     c.shwlk*(inbound.walkTime+outbound.walkTime)
                     + c.wlkaw0*p.auwk0 + c.wlkaw1*p.auwk1 + c.wlkaw2*p.auwk2
                     + c.wlkap0*p.aupr0 + c.wlkap1*p.aupr1 + c.wlkap2*p.aupr2
                     + c.wlkstp*p.totalStops
                     + c.noon*p.noon
               );
               hasUtility=true;
          };
               
     };
     
     public double getUtility(){
          if(!hasUtility){
               logger.fatal("Error: Utility not calculated for "+alternativeName+"\n");
              //TODO - log this error to the node exception file
               System.exit(1);
          };
          return utility;
     };
    


}
