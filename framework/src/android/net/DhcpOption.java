/*
 * Copyright (C) 2021 The Android Open Source Project
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

import java.util.Arrays;

/**
 * A class representing the custom DHCP option for network connection.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class DhcpOption implements Parcelable {
    // The data type of int for mType will be cast into byte in NetworkStack
    private final int mType;
    private final byte[] mValue;

    /** Constructor function */
    public DhcpOption(int type, @Nullable byte[] value) {
        if (type < 0 || type > 255) {
            throw new IllegalArgumentException("Invalid DHCP option type " + type);
        }
        mType = type;
        mValue = value;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeByteArray(mValue);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<DhcpOption> CREATOR =
            new Creator<DhcpOption>() {
                public DhcpOption createFromParcel(Parcel in) {
                    return new DhcpOption(in.readInt(), in.createByteArray());
                }

                public DhcpOption[] newArray(int size) {
                    return new DhcpOption[size];
                }
            };

    /** Get the type of DHCP option */
    public int getType() {
        return mType;
    }

    /** Get the value of DHCP option */
    @Nullable public byte[] getValue() {
        return mValue == null ? null : Arrays.copyOf(mValue, mValue.length);
    }
}
