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
package com.pb.tlumip.ct;

import org.apache.log4j.Logger;
import java.io.*;
import java.util.*;

public class ModalDistributions {
    final static Logger logger = Logger.getLogger("com.pb.tlumip.ct.ModelDistributions");
  List modes;
  int modeSize;
  HashMap hm;

  ModalDistributions (String filename) {
    modes = new ArrayList();
    hm = new HashMap();
    readDistributions (new File(filename));
    modeSize = modes.size();
  }

  // Read triangular distributions for commodity and mode of transport
  private void readDistributions (File f) {
    try {
      String source = f.getAbsolutePath();
      if(logger.isDebugEnabled()) {
        logger.debug("[# ModalDistributions.readDistributions()]");
        logger.debug("Reading data from "+source+" (LM: "+
          new Date(f.lastModified())+")");
      }
      BufferedReader br = new BufferedReader(new FileReader(source));
      String s, commodity, modeOfTransport;
      StringTokenizer st;
      double mean, alpha, beta, modeShare;
      while ((s = br.readLine()) != null) {
        if (s.startsWith("#")) continue;   // skip comments
        // Parse whitespace as well as commas as tokenizers, making , truck
        // the same as ,truck -- allowing the user to code the input file with
        // in just about any comma delimiter format they want
        st = new StringTokenizer(s, ", ");
        commodity = st.nextToken();
        modeOfTransport = st.nextToken();
        mean = Double.parseDouble(st.nextToken());
        alpha = Double.parseDouble(st.nextToken());  // minimum value
        beta = Double.parseDouble(st.nextToken());  // maximum value
        modeShare = Double.parseDouble(st.nextToken());
        if (modeShare==0.000) continue;   // why bother?
        if (!modes.contains(modeOfTransport)) modes.add(modeOfTransport);
        // Eventually we'll want to catch cases where a mode-commodity combo
        // has been defined twice. For now, let the program crash...
        hm.put(commodity+modeOfTransport,
          new TriangularDistribution(modeOfTransport, mean, alpha, beta,
            modeShare));
      }
      br.close();
      if(logger.isDebugEnabled()) {
        logger.debug("Modes found: "+modes);
        logger.debug("HashMap hm:\n"+hm);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // Given the interzonal distance between commodity origin (production) and destination
  // (consumption), return the mode of transport that will carry it
  public String selectMode (String commodity, double distance) {
    return selectMode (commodity, distance, Math.random());
  }
  public String selectMode (String commodity, double distance, double rn) {
    if(logger.isDebugEnabled()) {
        logger.debug("\n[# ModalDistributions.selectMode("+
                                            commodity+","+distance+","+rn+")]");
    }
    int m;   // index variable for modes
    double[] d = new double[modeSize];
    // Read the share-weighted densities (height of distribution at the given
    // distance) for each of the modes
    String key;
    double dsum = 0.0;
    if(logger.isDebugEnabled()) {
        logger.debug("Before normalization:");
    }
    for (m=0; m<modeSize; m++) {
      key = commodity+modes.get(m);
      if(logger.isDebugEnabled()) {
          logger.debug("   key="+key);
      }
      // Some commodity-mode combinations may be undefined, in which case we'll set them
      // equal to zero
      if (hm.containsKey(key))
        d[m] = ((TriangularDistribution)hm.get(key)).scaledDensity(distance);
      else d[m] = 0.0;
      dsum += d[m];
      if(logger.isDebugEnabled()) {
          logger.debug(", d="+d[m]+" dsum="+dsum);
      }
    }
    // If the sum of the densities is zero then the wheels fell off somewhere...
    if (dsum==0.0) {
      //System.out.println("Error: sum of densities is zero for "+
      //  "selectMode("+commodity+","+distance+","+rn+")");
      //System.exit(4);
      // Assign the default mode of truck
      return "UNKNOWN";
    }
    // Normalize the densities so that we can use them with a random draw on
    // (0,1) and put them in a cumulative distribution
    if(logger.isDebugEnabled()) {
        logger.debug("Cumulative normalized distribution:");
    }
    double floor = 0.0;
    for (m=0; m<modeSize; m++) {
      floor = d[m] = floor+(d[m]/dsum);
      if(logger.isDebugEnabled()) {
          logger.debug("   mode="+(String)modes.get(m)+" cf="+d[m]);
      }
    }
    // Finally, select the mode based on where the random draw falls in the
    // cumulative distribution
    String choice = (String)modes.get(0);  // Start by assuming its in first interval
    for (m=1; m<modeSize; m++)
      if ((rn>=d[m-1]) && (rn<=d[m])) {
        choice = (String)modes.get(m);
        break;
      }
    return choice;
  }

  public List getTransportModes() {
    return modes;
  }

  public static void main (String[] args) {
    
    ModalDistributions md = new ModalDistributions("ModalDistributionParameters.txt");
    // Should select truck
    System.out.println("At distance=17 mode="+md.selectMode("SCTG35", 17));
    // Should select rail
    System.out.println("At distance=920 mode="+md.selectMode("SCTG27", 920, 0.9));
    // Should crash the program
    System.out.println("At distance=-1 mode="+md.selectMode("SCTG14", -1));

  }

}