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

package com.pb.tlumip.ha;

import java.util.Collection;
import java.util.Iterator;
import java.util.Vector;

/** <p> This class represents a particular type of household, perhaps a lifecycle or an income category, or whatever. */
abstract public class HouseholdCategory {
    /**
     * A function which returns true if the given household fits into this category
     * @return whether the household fits into the category
     * @param h the household to test
     */
    public abstract boolean householdFits(Household h);

    /**
     * <p> Convenience method to see if the new household that would be formed if a person joined the existing household would
     * match the category.  (Does not resample attributes at the household level, like income or household preferences.)
     * @param h household without the extra person
     * @param p extra person to join the household
     */
    public boolean householdFits(Household h, Person p) {
        Household temp = h.tempCopy();
        temp.addPerson(p);
        return householdFits(temp);
    }

    public int getNumMaleAdults(Household h) { ensureSummarized(h); return numMaleAdults; }

    public int getNumFemaleAdults(Household h) { ensureSummarized(h); return numFemaleAdults; }

    public int getNumKids(Household h) { ensureSummarized(h); return numKids; }

    public int getNumPeople(Household h) { ensureSummarized(h); return numPeople; }

    private void calculateSummaries() {
        numMaleAdults = 0;
        numFemaleAdults = 0;
        numKids = 0;
        Iterator it = lastHouseholdInspected.getPeople().iterator();
        while (it.hasNext()) {
            Person tp = (Person)it.next();
            if (tp.age < 16) numKids++; else {
                if (tp.female) numFemaleAdults++; else numMaleAdults++;
            }
        }
        int numPeople = numMaleAdults + numFemaleAdults + numKids;
    }

    private int numMaleAdults;
    private int numFemaleAdults;
    private int numKids;
    private int numPeople;
    private Household lastHouseholdInspected = null;

    private void ensureSummarized(Household h) {
        if (lastHouseholdInspected != h) {
            lastHouseholdInspected = h;
            calculateSummaries();
        }
    }

    public Person swapInPerson(Household h, Person p) {
        Household hCopy = h.tempCopy();
        Vector mixedUpBagOfPeople = new Vector(hCopy.getPeople());
        java.util.Collections.shuffle(mixedUpBagOfPeople);
        Person[] members = new Person[mixedUpBagOfPeople.size()];
        members = (Person[]) mixedUpBagOfPeople.toArray(members);
        for (int i = 0; i < members.length; i++) {
            Person swapOut = members[i];
            if (swapOut == p) return swapOut;
            hCopy.removePerson(swapOut);
            hCopy.addPerson(p);
            if (this.householdFits(hCopy)) {
                p.addToHousehold(h);
                swapOut.removeFromHousehold();
                return swapOut;
            }
            hCopy.removePerson(p);
            hCopy.addPerson(swapOut);
        }
        return null;
    }

	abstract public Household createANewHousehold(Collection people);
	abstract public Household createANewHousehold();
}
