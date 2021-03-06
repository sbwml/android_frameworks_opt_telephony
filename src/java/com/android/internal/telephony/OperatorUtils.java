/*
 * Copyright (C) 2016 The MoKee Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.os.Environment;
import android.telephony.Rlog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import java.util.Locale;

public class OperatorUtils {

    private static boolean isSupportLanguage(boolean excludeSAR) {
        Locale locale = Locale.getDefault();
        if (locale.getLanguage().startsWith(Locale.CHINESE.getLanguage())) {
            if (excludeSAR) {
                return locale.getCountry().equals("CN");
            } else {
                return !locale.getCountry().equals("SG");
            }
        } else {
            return false;
        }
    }

    // Initialize list of Operator codes
    // this will be taken care of when garbage collection starts.
    private HashMap<String, String> initList() {
        HashMap<String, String> init = new HashMap<String, String>();
        // taken from spnOveride.java

        FileReader spnReader;

        final File spnFile = new File(Environment.getRootDirectory(),
                "etc/selective-spn-conf.xml");

        try {
            spnReader = new FileReader(spnFile);
        } catch (FileNotFoundException e) {
            Rlog.w("Operatorcheck", "Can not open " +
                    Environment.getRootDirectory() + "/etc/selective-spn-conf.xml");
            return init;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(spnReader);

            XmlUtils.beginDocument(parser, "spnOverrides");

            while (true) {
                XmlUtils.nextElement(parser);

                String name = parser.getName();
                if (!"spnOverride".equals(name)) {
                    break;
                }

                String numeric = parser.getAttributeValue(null, "numeric");
                String data = parser.getAttributeValue(null, "spn");

                init.put(numeric, data);
            }
        } catch (XmlPullParserException e) {
            Rlog.w("Operatorcheck", "Exception in spn-conf parser " + e);
        } catch (IOException e) {
            Rlog.w("Operatorcheck", "Exception in spn-conf parser " + e);
        }
        return init;
    }

    // this will stay persistant in memory when called
    private static String stored = null;
    private static String storedOperators = null;

    public static String operatorReplace(String longName, String numeric){
        // sanity checking if the value is actually not equal to the range apn
        // numerics
        // if it is null, check your ril class.
        if (numeric == null || !numeric.startsWith("460")
           || (5 != numeric.length() && numeric.length() != 6)
           || !isSupportLanguage(false)){
            return longName;
        }
        // this will check if the stored value is equal to other.
        // this uses a technique called last known of good value.
        // along with sanity checking
        if (storedOperators != null && stored != null && stored.equals(numeric)){
            return storedOperators;
        }
        stored = numeric;
        try {
            // this will find out if it a number then it will catch it based
            // on invalid chars.
            Integer.parseInt(numeric);
        }  catch(NumberFormatException E){
            // not a number, pass it along to stored operator until the next
            // round.
            storedOperators = numeric;
            return storedOperators;
        }
        // this code will be taking care of when garbage collection start
        OperatorUtils init = new OperatorUtils();
        Map<String, String> operators = init.initList();
        storedOperators = operators.containsKey(numeric) ? operators.get(numeric) : longName;
        return storedOperators;
    }

}
