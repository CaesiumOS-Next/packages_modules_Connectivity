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

package com.android.server.connectivity;


import android.annotation.NonNull;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;


/**
 * The class for parsing and checking the self-declared application network capabilities.
 *
 * ApplicationSelfCertifiedNetworkCapabilities is an immutable class that
 * can parse the self-declared application network capabilities in the application resources. The
 * class also provides a helper method to check whether the requested network capabilities
 * already self-declared.
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

    private long mRequestedNetworkCapabilities = 0;

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
     * @return An ApplicationSelfCertifiedNetworkCapabilities object.
     * @throws InvalidTagException    if the capabilities in xml config contains invalid tag.
     * @throws XmlPullParserException if xml parsing failed.
     * @throws IOException            if unable to read the xml file properly.
     */
    @NonNull
    public static ApplicationSelfCertifiedNetworkCapabilities createFromXml(
            @NonNull final XmlPullParser xmlParser)
            throws InvalidTagException, XmlPullParserException, IOException {
        final ApplicationSelfCertifiedNetworkCapabilities.Builder builder =
                new ApplicationSelfCertifiedNetworkCapabilities.Builder();
        builder.parseXml(xmlParser);
        return builder.build();
    }

    private ApplicationSelfCertifiedNetworkCapabilities(@NonNull final Builder builder) {
        mRequestedNetworkCapabilities = builder.getRequestedNetworkCapabilities();
    }

    /**
     * Enforces self-certified capabilities are declared.
     *
     * @param networkCapabilities the input NetworkCapabilities to check against.
     * @throws SecurityException if the capabilities are not properly self-declared.
     */
    public void enforceIfSelfCertifiedNetworkCapabilitiesDeclared(
            @NonNull final NetworkCapabilities networkCapabilities) {
        if (networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH)
                && !hasPrioritizeBandwidth()) {
            throw new SecurityException(
                    "Missing " + ApplicationSelfCertifiedNetworkCapabilities.PRIORITIZE_BANDWIDTH
                            + " declaration");
        }
        if (networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY)
                && !hasPrioritizeLatency()) {
            throw new SecurityException(
                    "Missing " + ApplicationSelfCertifiedNetworkCapabilities.PRIORITIZE_LATENCY
                            + " declaration");
        }
    }

    /**
     * Checks if NET_CAPABILITY_PRIORITIZE_LATENCY is declared.
     */
    private boolean hasPrioritizeLatency() {
        return (mRequestedNetworkCapabilities & (1L
                << NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY)) != 0;
    }

    /**
     * Checks if NET_CAPABILITY_PRIORITIZE_BANDWIDTH is declared.
     */
    private boolean hasPrioritizeBandwidth() {
        return (mRequestedNetworkCapabilities & (1L
                << NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH)) != 0;
    }

    /**
     * Builder class for {@link ApplicationSelfCertifiedNetworkCapabilities} objects.
     */
    public static final class Builder {

        private long mRequestedNetworkCapabilities = 0;

        public long getRequestedNetworkCapabilities() {
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
                throws InvalidTagException, XmlPullParserException, IOException {
            while (xmlParser.next() != XmlPullParser.START_TAG) {
                continue;
            }

            // Validates the tag is "network-capabilities-declaration"
            if (!xmlParser.getName().equals(NETWORK_CAPABILITIES_DECLARATION_TAG)) {
                throw new InvalidTagException("Invalid tag: " + xmlParser.getName());
            }

            xmlParser.next();
            while (xmlParser.getEventType() != XmlPullParser.END_TAG) {
                while (xmlParser.getEventType() != XmlPullParser.START_TAG) {
                    if (xmlParser.getEventType() == XmlPullParser.END_DOCUMENT) {
                        return;
                    }
                    xmlParser.next();
                }

                // Validates the tag is "uses-network-capability".
                if (xmlParser.getName().equals(USES_NETWORK_CAPABILITY_TAG)) {
                    parseDeclarationTag(xmlParser);
                } else {
                    Log.w(TAG, "Invalid tag: " + xmlParser.getName());
                }
                while (xmlParser.getEventType() != XmlPullParser.END_TAG) {
                    xmlParser.next();
                }
                xmlParser.next();
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
                        addCapability(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
                        break;
                    case PRIORITIZE_LATENCY:
                        addCapability(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY);
                        break;
                    default:
                        Log.w(TAG, "Unknown priority declaration name: " + name);
                }
            } else {
                throw new IllegalArgumentException(
                        "uses-network-capability name must be specified");
            }
        }

        private void addCapability(int capability) {
            mRequestedNetworkCapabilities |= 1L << capability;
        }
    }
}
