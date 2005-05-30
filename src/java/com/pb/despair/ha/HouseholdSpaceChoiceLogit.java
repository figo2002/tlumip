/* Generated by Together */

package com.pb.despair.ha;

import com.pb.models.pecas.ChoiceModelOverflowException;
import com.pb.models.pecas.*;

import java.util.Iterator;

public class HouseholdSpaceChoiceLogit extends LogitModel {

    HouseholdSpaceChoiceLogit(AllHouseholds ahh) {
        Iterator i = ahh.getAllowedIn().iterator();
        while (i.hasNext()) {
          DevelopmentTypeInterface dt = (DevelopmentTypeInterface) i.next();
          addAlternative(new SpaceChoiceAlternative(dt));
        }
    }
    Household h;
    AbstractTAZ z;

    class SpaceChoiceAlternative implements Alternative {
        DevelopmentTypeInterface dt;
        SpaceChoiceAlternative(DevelopmentTypeInterface dt) {
            this.dt = dt;
        }

        public double getUtility(double higherLevelDispersionParameter) {
            if (h==null) throw new Error("Forgot to set household in SpaceChoiceLogitModel");
            if (z==null) throw new Error("Forgot to set zone in SpaceChoiceLogitModel");
            return h.utilityOfSpaceAndPrice(z,dt, true, Household.dwellingChoiceDispersionParameter);
        }


    }
    DevelopmentTypeInterface monteCarloSpaceChoice() throws NoAlternativeAvailable, ChoiceModelOverflowException {
        return ((SpaceChoiceAlternative) super.monteCarloChoice()).dt;
    }

}

