/* Generated by Together */

package com.pb.despair.ha;

import java.util.Hashtable;
import java.util.Iterator;

/** <p> This class is a list of possible household types. */
public class HouseholdPossibilities {
    public double sumWeights(Household h) {
        double sum = 0;
        Iterator it = individualPossibilities.keySet().iterator();
        while (it.hasNext()) {
            HouseholdCategory hc = (HouseholdCategory)it.next();
            if (hc.householdFits(h)) {
                sum += ((Double)individualPossibilities.get(hc)).doubleValue();
            }
        }
        return sum;
    }

    public double sumWeights(Household h, Person p) {
        double sum = 0;
        Iterator it = individualPossibilities.keySet().iterator();
        while (it.hasNext()) {
            HouseholdCategory hc = (HouseholdCategory)it.next();
            if (hc.householdFits(h, p)) {
                sum += ((Double)individualPossibilities.get(hc)).doubleValue();
            }
        }
        return sum;
    }

    public void add(HouseholdCategory householdCategory, double weight) {
        individualPossibilities.put(householdCategory, new Double(weight));
    }

    public HouseholdCategory randomCategory() {
        Iterator it = individualPossibilities.keySet().iterator();
        double sum = 0;
        HouseholdCategory hc=null;
        while (it.hasNext()) {
            hc = (HouseholdCategory)it.next();
            sum += ((Double)individualPossibilities.get(hc)).doubleValue();
        }
        double selector = Math.random() * sum;
        sum = 0;
        it = individualPossibilities.keySet().iterator();
        while (sum <= selector) {
            hc = (HouseholdCategory)it.next();
            sum+= ((Double)individualPossibilities.get(hc)).doubleValue();
        }
        return hc;
    }

    /**
     * 
     */
    private Hashtable individualPossibilities = new Hashtable(10);
}

