/*
 * Copyright 2023 The Android Open Source Project
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

package android.remoteauth;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Class represents a discovered device that can be used as remote authenticator.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class DiscoveredDevice implements Parcelable {
    @NonNull private final String mName;
    private final boolean mRegistered; // represents if device is already registered

    /** Used to read a DiscoveredDevice from a Parcel. */
    public static final @NonNull Creator<DiscoveredDevice> CREATOR =
            new Creator<>() {
                @Override
                public DiscoveredDevice createFromParcel(Parcel in) {
                    DiscoveredDevice.Builder builder = new DiscoveredDevice.Builder();
                    builder.setName(in.readString());
                    builder.setRegistered(in.readBoolean());

                    return builder.build();
                }

                @Override
                public DiscoveredDevice[] newArray(int size) {
                    return new DiscoveredDevice[size];
                }
            };

    public DiscoveredDevice(@NonNull String name, boolean registered) {
        this.mName = name;
        this.mRegistered = registered;
    }

    /** Gets the name of the DiscoveredDevice device. */
    @Nullable
    public String getName() {
        return mName;
    }

    /** If DiscoveredDevice is already registered as remote authenticator. */
    public boolean isRegistered() {
        return mRegistered;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DiscoveredDevice [");
        sb.append("name=").append(mName).append(", ");
        sb.append("registered=").append(mRegistered);
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof DiscoveredDevice) {
            DiscoveredDevice otherDevice = (DiscoveredDevice) other;
            return Objects.equals(this.mName, otherDevice.mName)
                    && this.isRegistered() == otherDevice.isRegistered();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mRegistered);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        String name = getName();
        dest.writeString(name);
        dest.writeBoolean(isRegistered());
    }

    /**
     * Builder for {@link DiscoveredDevice} objects.
     *
     * @hide
     */
    public static final class Builder {
        @Nullable private String mName;
        private boolean mRegistered; // represents if device is already registered

        public Builder() {}

        /**
         * Sets the name of the DiscoveredDevice device.
         *
         * @param name Name of the DiscoveredDevice. Can be {@code null} if there is no name.
         */
        @NonNull
        public DiscoveredDevice.Builder setName(@NonNull String name) {
            this.mName = name;
            return this;
        }

        /**
         * Sets the name of the DiscoveredDevice device.
         *
         * @param registered of the DiscoveredDevice.
         */
        @NonNull
        public DiscoveredDevice.Builder setRegistered(boolean registered) {
            this.mRegistered = registered;
            return this;
        }

        /**
         * Creates the {@link DiscoveredDevice} instance.
         *
         * @return the configured {@link DiscoveredDevice} instance.
         */
        @NonNull
        public DiscoveredDevice build() {
            return new DiscoveredDevice(mName, mRegistered);
        }
    }
}
