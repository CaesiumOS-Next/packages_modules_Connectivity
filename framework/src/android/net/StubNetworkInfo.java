/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;


/**
 * Describes the information of a stub network.
 *
 * <p>Note that this is just a holder of data. Modifying it does not affect live networks.
 *
 * @hide
 */
@SystemApi
public final class StubNetworkInfo implements Parcelable {
    private final String mAilInterfaceName;

    public class MulticastRoutingConfig {
        private boolean mIsEnabled = false;
        private boolean mAllowAllOnScope = false;
        private int mMinScope = 4;
        private ArrayList<Inet6Address> mMulticastListeningAddresses = new ArrayList<>();

        /*
         * Returns if multicast routing is enabled
         */
        public boolean isMulticastRoutingEnabled() {
            return mIsEnabled;
        }

        public void setMulticastRoutingEnabled(boolean isEnabled) {
            mIsEnabled = isEnabled;
        }

        /*
         * Returns if multicast routing is allowed with any group addresses.
         *     When true, multicast routing to any group address with scope >= getMinScope() is allowed
         *     When false, multicast routing to group address listed in getMulticastListeningAddresses() is allowed
         */
        public boolean isAllowAllOnScope() {
            return mAllowAllOnScope;
        }

        public void setAllowAllOnScope(boolean isAllowed) {
            mAllowAllOnScope = isAllowed;
        }

        /*
         * Returns the minimal group address scope that is allowed for forwarding.
         */
        public int getMinScope() {
            return mMinScope;
        }
        public void setMinScope(int scope) {
            mMinScope = scope;
        }

        private int getListeningAddressIndex(Inet6Address address) {
            for (int i = 0; i < mMulticastListeningAddresses.size(); i++) {
                if (mMulticastListeningAddresses.get(i) == address) {
                    return i;
                }
            }
            return -1;
        }

        public void addMulticastListeningAddress(@NonNull Inet6Address address) {
            if (getListeningAddressIndex(address) < 0) {
                mMulticastListeningAddresses.add(address);
            }
        }

        public void removeMulticastListeningAddress(@NonNull Inet6Address address) {
            int idx = getListeningAddressIndex(address);
            if (idx >= 0) {
                mMulticastListeningAddresses.remove(idx);
            }
        }

        /*
         * Returns the list of group addresses listened by the outgoing interface.
         */
        public @NonNull List<Inet6Address> getMulticastListeningAddresses() {
            final List<Inet6Address> addresses = new ArrayList<>();
            for (Inet6Address addr : mMulticastListeningAddresses) {
                addresses.add(addr);
            }
            return Collections.unmodifiableList(addresses);
        }
    }

    public final @NonNull MulticastRoutingConfig upstreamConfig = new MulticastRoutingConfig();
    public final @NonNull MulticastRoutingConfig downstreamConfig = new MulticastRoutingConfig();

    /** @hide */
    @SystemApi
    public StubNetworkInfo(@Nullable String ailInterfaceName) {
        mAilInterfaceName = new String(ailInterfaceName);
    }

    public @Nullable String getAilInterfaceName() {
        return mAilInterfaceName;
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(getAilInterfaceName());
    }

    @Override
    public int hashCode() {
        return getAilInterfaceName().hashCode();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof StubNetworkInfo)) {
            return false;
        } else {
            StubNetworkInfo otherStubNetworkInfo = (StubNetworkInfo) other;
            return getAilInterfaceName() == otherStubNetworkInfo.getAilInterfaceName();
        }
    }

    public static final @NonNull Creator<StubNetworkInfo> CREATOR =
            new Creator<StubNetworkInfo>() {
                public StubNetworkInfo createFromParcel(Parcel in) {
                    return new StubNetworkInfo(in.readString());
                }

                public StubNetworkInfo[] newArray(int size) {
                    return new StubNetworkInfo[size];
                }
            };
}
