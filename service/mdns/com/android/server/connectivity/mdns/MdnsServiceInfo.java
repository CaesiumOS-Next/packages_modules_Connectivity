/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A class representing a discovered mDNS service instance.
 *
 * @hide
 */
public class MdnsServiceInfo implements Parcelable {
    /** @hide */
    public static final Parcelable.Creator<MdnsServiceInfo> CREATOR =
            new Parcelable.Creator<MdnsServiceInfo>() {

                @Override
                public MdnsServiceInfo createFromParcel(Parcel source) {
                    return new MdnsServiceInfo(
                            source.readString(),
                            source.createStringArray(),
                            source.createStringArrayList(),
                            source.createStringArray(),
                            source.readInt(),
                            source.readString(),
                            source.readString(),
                            source.createStringArrayList());
                }

                @Override
                public MdnsServiceInfo[] newArray(int size) {
                    return new MdnsServiceInfo[size];
                }
            };

    private final String mServiceInstanceName;
    private final String[] mServiceType;
    private final List<String> mSubtypes;
    private final String[] mHostName;
    private final int mPort;
    private final String mIpv4Address;
    private final String mIpv6Address;
    private final Map<String, String> mAttributes = new HashMap<>();
    List<String> mTextStrings;

    /**
     * Constructs a {@link MdnsServiceInfo} object with default values.
     *
     * @hide
     */
    public MdnsServiceInfo(
            String serviceInstanceName,
            String[] serviceType,
            List<String> subtypes,
            String[] hostName,
            int port,
            String ipv4Address,
            String ipv6Address,
            List<String> textStrings) {
        mServiceInstanceName = serviceInstanceName;
        mServiceType = serviceType;
        mSubtypes = new ArrayList<>();
        if (subtypes != null) {
            mSubtypes.addAll(subtypes);
        }
        mHostName = hostName;
        mPort = port;
        mIpv4Address = ipv4Address;
        mIpv6Address = ipv6Address;
        if (textStrings != null) {
            for (String text : textStrings) {
                int pos = text.indexOf('=');
                if (pos < 1) {
                    continue;
                }
                mAttributes.put(text.substring(0, pos).toLowerCase(Locale.ENGLISH),
                        text.substring(++pos));
            }
        }
    }

    /** @return the name of this service instance. */
    public String getServiceInstanceName() {
        return mServiceInstanceName;
    }

    /** @return the type of this service instance. */
    public String[] getServiceType() {
        return mServiceType;
    }

    /** @return the list of subtypes supported by this service instance. */
    public List<String> getSubtypes() {
        return new ArrayList<>(mSubtypes);
    }

    /**
     * @return {@code true} if this service instance supports any subtypes.
     *         {@code false} if this service instance does not support any subtypes.
     */
    public boolean hasSubtypes() {
        return !mSubtypes.isEmpty();
    }

    /** @return the host name of this service instance. */
    public String[] getHostName() {
        return mHostName;
    }

    /** @return the port number of this service instance. */
    public int getPort() {
        return mPort;
    }

    /** @return the IPV4 address of this service instance. */
    public String getIpv4Address() {
        return mIpv4Address;
    }

    /** @return the IPV6 address of this service instance. */
    public String getIpv6Address() {
        return mIpv6Address;
    }

    /**
     * @return the attribute value for {@code key}.
     *         {@code null} if no attribute value exists for {@code key}.
     */
    public String getAttributeByKey(@NonNull String key) {
        return mAttributes.get(key.toLowerCase(Locale.ENGLISH));
    }

    /** @return an immutable map of all attributes. */
    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(mAttributes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (mTextStrings == null) {
            // Lazily initialize the parcelable field mTextStrings.
            mTextStrings = new ArrayList<>(mAttributes.size());
            for (Map.Entry<String, String> kv : mAttributes.entrySet()) {
                mTextStrings.add(String.format(Locale.ROOT, "%s=%s", kv.getKey(), kv.getValue()));
            }
        }

        out.writeString(mServiceInstanceName);
        out.writeStringArray(mServiceType);
        out.writeStringList(mSubtypes);
        out.writeStringArray(mHostName);
        out.writeInt(mPort);
        out.writeString(mIpv4Address);
        out.writeString(mIpv6Address);
        out.writeStringList(mTextStrings);
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "Name: %s, subtypes: %s, ip: %s, port: %d",
                mServiceInstanceName,
                TextUtils.join(",", mSubtypes),
                mIpv4Address,
                mPort);
    }
}
