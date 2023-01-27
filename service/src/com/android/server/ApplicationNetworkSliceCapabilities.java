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
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.HashSet;
import java.util.Set;


/**
 * ApplicationNetworkSliceCapabilities is an immutable class that encapsulates an application's
 * capabilities for requesting 5g network slicing.
 */
public final class ApplicationNetworkSliceCapabilities {

    public static final String PRIORITIZE_LATENCY = "NET_CAPABILITY_PRIORITIZE_LATENCY";
    public static final String PRIORITIZE_BANDWIDTH = "NET_CAPABILITY_PRIORITIZE_BANDWIDTH";

    private static final String TAG = ApplicationNetworkSliceCapabilities.class.getSimpleName();
    private static final String NETWORK_SLICE_TAG = "network-slice";
    private static final String DECLARATION_TAG = "declaration";
    private static final String NAME_TAG = "name";

    private final Set<String> mRequestedNetworkSlice = new HashSet<>();

    /**
     * Creates {@link ApplicationNetworkSliceCapabilities} from an xml.
     *
     * <p> Here is an example of the xml syntax:
     *
     * <pre>
     * {@code
     * <network-slice xmlns:android="http://schemas.android.com/apk/res/android">
     *     <declaration android:name="NET_CAPABILITY_PRIORITIZE_LATENCY"/>
     *     <declaration android:name="NET_CAPABILITY_PRIORITIZE_BANDWIDTH"/>
     * </network-slice>
     * }
     * </pre>
     * <p>
     *
     * @param xmlParser The underlying {@link XmlPullParser} that will read the xml.
     * @return An ApplicationNetworkSliceCapabilities object.
     * @throws UnsupportedOperationException if the capabilities in xml config are invalid or
     *                                       incompatible.
     */
    @NonNull
    public static ApplicationNetworkSliceCapabilities createFromXml(
            @NonNull XmlPullParser xmlParser) {
        ApplicationNetworkSliceCapabilities.Builder builder =
                new ApplicationNetworkSliceCapabilities.Builder();
        builder.parseXml(xmlParser);
        return builder.build();
    }

    private ApplicationNetworkSliceCapabilities(Builder builder) {
        mRequestedNetworkSlice.addAll(builder.getRequestedNetworkSlice());
    }

    /**
     * Check if NET_CAPABILITY_PRIORITIZE_LATENCY is declared.
     */
    public boolean isNetCapabilityPrioritizeLatency() {
        return mRequestedNetworkSlice.contains(PRIORITIZE_LATENCY);
    }

    /**
     * Check if NET_CAPABILITY_PRIORITIZE_BANDWIDTH is declared.
     */
    public boolean isNetCapabilityPrioritizeBandwidth() {
        return mRequestedNetworkSlice.contains(PRIORITIZE_BANDWIDTH);
    }

    /**
     * Builder class for {@link ApplicationNetworkSliceCapabilities} objects.
     */
    public static final class Builder {

        private Set<String> mRequestedNetworkSlice = new HashSet<>();

        public Set<String> getRequestedNetworkSlice() {
            return mRequestedNetworkSlice;
        }

        public Builder() {
        }

        /**
         * Builds a {@link ApplicationNetworkSliceCapabilities} object.
         *
         * @return a new {@link ApplicationNetworkSliceCapabilities} instance successfully
         * initialized
         * @throws UnsupportedOperationException if the parameters set on the
         *                                       <code>Builder</code> were incompatible.
         */
        @NonNull
        public ApplicationNetworkSliceCapabilities build() {
            return new ApplicationNetworkSliceCapabilities(this);
        }

        private void parseXml(@NonNull XmlPullParser xmlParser)
                throws UnsupportedOperationException {
            try {
                while (xmlParser.next() != XmlPullParser.START_TAG) {
                    continue;
                }

                // Validates the tag is "network-slice"
                if (!xmlParser.getName().equals(NETWORK_SLICE_TAG)) {
                    throw new UnsupportedOperationException("Invalid tag: " + xmlParser.getName());
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
                    if (xmlParser.getName().equals(DECLARATION_TAG)) {
                        parseDeclarationTag(xmlParser);
                    } else {
                        throw new UnsupportedOperationException(
                                "Invalid tag: " + xmlParser.getName());
                    }
                    while (xmlParser.getEventType() != XmlPullParser.END_TAG) {
                        xmlParser.next();
                    }
                    xmlParser.next();
                }
            } catch (XmlPullParserException xppe) {
                throw new UnsupportedOperationException("Ill-formatted xml file");
            } catch (java.io.IOException ioe) {
                throw new UnsupportedOperationException("Unable to read xml file");
            }
        }

        private void parseDeclarationTag(XmlPullParser xmlParser) {
            String name = null;
            for (int i = 0; i < xmlParser.getAttributeCount(); i++) {
                String attrName = xmlParser.getAttributeName(i);
                if (attrName.equals(NAME_TAG)) {
                    name = xmlParser.getAttributeValue(i);
                } else {
                    throw new UnsupportedOperationException("Invalid attribute name: " + attrName);
                }
            }
            if (name != null) {
                switch (name) {
                    case PRIORITIZE_BANDWIDTH:
                        mRequestedNetworkSlice.add(PRIORITIZE_BANDWIDTH);
                        break;
                    case PRIORITIZE_LATENCY:
                        mRequestedNetworkSlice.add(PRIORITIZE_LATENCY);
                        break;
                    default:
                        Log.w(TAG, "Invalid priority declaration name: " + name);
                }
            } else {
                throw new UnsupportedOperationException(
                        "Network slice name must be specified");
            }
        }
    }
}
