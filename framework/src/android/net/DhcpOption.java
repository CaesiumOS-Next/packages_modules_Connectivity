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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class representing the custom DHCP option for network connection.
 *
 * @hide
 */
@SystemApi
public final class DhcpOption implements Parcelable {
    private byte mType;
    private byte[] mValue;

    /** Create a new DhcpOption object */
    private DhcpOption(byte type, byte[] value) {
        mType = type;
        mValue = value;
    }

    /** Builder for DhcpOption */
    public static final class Builder {
        private byte mType = 0;
        private byte[] mValue;

        /** Set the type of DHCP option */
        @NonNull public Builder setType(byte type) {
            mType = type;
            return this;
        }

        /** Set the value of DHCP option */
        @NonNull public Builder setValue(@NonNull byte[] value) {
            mValue = value;
            return this;
        }

        /** Build the DhcpOption object represented by this builder */
        @NonNull public DhcpOption build() {
            if (mValue == null) {
                throw new IllegalArgumentException("The value of DHCP option, "
                        + "mValue, cannot be null");
            }
            return new DhcpOption(mType, mValue);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeByte(mType);
        dest.writeByteArray(mValue);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<DhcpOption> CREATOR =
            new Creator<DhcpOption>() {
                public DhcpOption createFromParcel(Parcel in) {
                    return new DhcpOption(in.readByte(), in.createByteArray());
                }

                public DhcpOption[] newArray(int size) {
                    return new DhcpOption[size];
                }
            };

    /** Get the type of DHCP option */
    public byte getType() {
        return mType;
    }

    /** Get the value of DHCP option */
    @NonNull public byte[] getValue() {
        return mValue;
    }
}
