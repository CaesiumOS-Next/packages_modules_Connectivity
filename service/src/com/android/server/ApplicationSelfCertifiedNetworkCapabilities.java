/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server;


import android.annotation.NonNull;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.HashSet;
import java.util.Set;


/**
 * ApplicationSelfCertifiedNetworkCapabilities is an immutable class an immutable class that
 * contains the application network properties declared in its manifest about the capabilities that
 * need declaration.
 *
 * <p> Starting from Android 14, the application has to explicitly declare what kind of network
 * capabilities the application wants to have in a self_certified_network_capabilities.xml file
 * resource file and add it as a property tag in the AndroidManifest.xml file.
 *
 * Here is an example:
 * <pre>
 *  {@code
 *  <network-capabilities-declaration xmlns:android="http://schemas.android.com/apk/res/android">
 *     <uses-network-capability android:name="NET_CAPABILITY_PRIORITIZE_LATENCY"/>
 *     <uses-network-capability android:name="NET_CAPABILITY_PRIORITIZE_BANDWIDTH"/>
 * </network-capabilities-declaration>
 *  }
 *  </pre>
 */
public final class ApplicationSelfCertifiedNetworkCapabilities {

    public static final String PRIORITIZE_LATENCY = "NET_CAPABILITY_PRIORITIZE_LATENCY";
    public static final String PRIORITIZE_BANDWIDTH = "NET_CAPABILITY_PRIORITIZE_BANDWIDTH";

    private static final String TAG =
            ApplicationSelfCertifiedNetworkCapabilities.class.getSimpleName();
    private static final String NETWORK_CAPABILITIES_DECLARATION_TAG =
            "network-capabilities-declaration";
    private static final String USES_NETWORK_CAPABILITY_TAG = "uses-network-capability";
    private static final String NAME_TAG = "name";

    private final Set<Integer> mRequestedNetworkCapabilities = new HashSet<>();

    /**
     * Creates {@link ApplicationSelfCertifiedNetworkCapabilities} from a xml file.
     *
     * <p> Here is an example of the xml syntax:
     *
     * <pre>
     * {@code
     *  <network-capabilities-declaration xmlns:android="http://schemas.android.com/apk/res/android">
     *     <uses-network-capability android:name="NET_CAPABILITY_PRIORITIZE_LATENCY"/>
     *     <uses-network-capability android:name="NET_CAPABILITY_PRIORITIZE_BANDWIDTH"/>
     * </network-capabilities-declaration>
     * }
     * </pre>
     * <p>
     *
     * @param xmlParser The underlying {@link XmlPullParser} that will read the xml.
     * @return An ApplicationNetworkSliceCapabilities object.
     * @throws IllegalArgumentException if the capabilities in xml config are invalid.
     */
    @NonNull
    public static ApplicationSelfCertifiedNetworkCapabilities createFromXml(
            @NonNull final XmlPullParser xmlParser) {
        final ApplicationSelfCertifiedNetworkCapabilities.Builder builder =
                new ApplicationSelfCertifiedNetworkCapabilities.Builder();
        builder.parseXml(xmlParser);
        return builder.build();
    }

    private ApplicationSelfCertifiedNetworkCapabilities(@NonNull final Builder builder) {
        mRequestedNetworkCapabilities.addAll(builder.getRequestedNetworkCapabilities());
    }

    /**
     * Checks if NET_CAPABILITY_PRIORITIZE_LATENCY is declared.
     */
    public boolean hasPrioritizeLatency() {
        return mRequestedNetworkCapabilities.contains(
                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY);
    }

    /**
     * Checks if NET_CAPABILITY_PRIORITIZE_BANDWIDTH is declared.
     */
    public boolean hasPrioritizeBandwidth() {
        return mRequestedNetworkCapabilities.contains(
                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
    }

    /**
     * Builder class for {@link ApplicationSelfCertifiedNetworkCapabilities} objects.
     */
    public static final class Builder {

        private final Set<Integer> mRequestedNetworkCapabilities = new HashSet<>();

        public Set<Integer> getRequestedNetworkCapabilities() {
            return mRequestedNetworkCapabilities;
        }

        public Builder() {
        }

        /**
         * Builds a {@link ApplicationSelfCertifiedNetworkCapabilities} object.
         *
         * @return a new {@link ApplicationSelfCertifiedNetworkCapabilities} instance successfully
         * initialized
         */
        @NonNull
        public ApplicationSelfCertifiedNetworkCapabilities build() {
            return new ApplicationSelfCertifiedNetworkCapabilities(this);
        }

        private void parseXml(@NonNull final XmlPullParser xmlParser)
                throws IllegalArgumentException {
            try {
                while (xmlParser.next() != XmlPullParser.START_TAG) {
                    continue;
                }

                // Validates the tag is "network-capabilities-declaration"
                if (!xmlParser.getName().equals(NETWORK_CAPABILITIES_DECLARATION_TAG)) {
                    throw new IllegalArgumentException("Invalid tag: " + xmlParser.getName());
                }

                xmlParser.next();
                while (xmlParser.getEventType() != XmlPullParser.END_TAG) {
                    while (xmlParser.getEventType() != XmlPullParser.START_TAG) {
                        if (xmlParser.getEventType() == XmlPullParser.END_DOCUMENT) {
                            return;
                        }
                        xmlParser.next();
                    }

                    // Validates the tag is "format".
                    if (xmlParser.getName().equals(USES_NETWORK_CAPABILITY_TAG)) {
                        parseDeclarationTag(xmlParser);
                    } else {
                        throw new IllegalArgumentException(
                                "Invalid tag: " + xmlParser.getName());
                    }
                    while (xmlParser.getEventType() != XmlPullParser.END_TAG) {
                        xmlParser.next();
                    }
                    xmlParser.next();
                }
            } catch (XmlPullParserException xppe) {
                throw new IllegalArgumentException("Ill-formatted xml file");
            } catch (java.io.IOException ioe) {
                throw new IllegalArgumentException("Unable to read xml file");
            }
        }

        private void parseDeclarationTag(@NonNull final XmlPullParser xmlParser) {
            String name = null;
            for (int i = 0; i < xmlParser.getAttributeCount(); i++) {
                final String attrName = xmlParser.getAttributeName(i);
                if (attrName.equals(NAME_TAG)) {
                    name = xmlParser.getAttributeValue(i);
                } else {
                    throw new IllegalArgumentException("Invalid attribute name: " + attrName);
                }
            }
            if (name != null) {
                switch (name) {
                    case PRIORITIZE_BANDWIDTH:
                        mRequestedNetworkCapabilities.add(
                                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
                        break;
                    case PRIORITIZE_LATENCY:
                        mRequestedNetworkCapabilities.add(
                                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY);
                        break;
                    default:
                        Log.w(TAG, "Unknown priority declaration name: " + name);
                }
            } else {
                throw new IllegalArgumentException(
                        "uses-network-capability name must be specified");
            }
        }
    }
}
