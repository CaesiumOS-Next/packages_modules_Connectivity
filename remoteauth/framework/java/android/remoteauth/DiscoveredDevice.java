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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Discovered device that can be registered as remote authenticator.
 *
 * @hide
 */
// TODO(b/295407748) Change to use @DataClass
@SystemApi(client = MODULE_LIBRARIES)
public final class DiscoveredDevice implements Parcelable {
    public static final int STATE_NOT_REGISTERED = 0;
    public static final int STATE_REGISTERED = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_NOT_REGISTERED, STATE_REGISTERED})
    @interface RegistrationState {}

    @NonNull private final String mName;
    private final @RegistrationState int mRegistrationState;

    public static final @NonNull Creator<DiscoveredDevice> CREATOR =
            new Creator<>() {
                @Override
                public DiscoveredDevice createFromParcel(Parcel in) {
                    DiscoveredDevice.Builder builder = new DiscoveredDevice.Builder();
                    builder.setName(in.readString());
                    builder.setRegistrationState(in.readInt());

                    return builder.build();
                }

                @Override
                public DiscoveredDevice[] newArray(int size) {
                    return new DiscoveredDevice[size];
                }
            };

    public DiscoveredDevice(@NonNull String name, @RegistrationState int registrationState) {
        this.mName = name;
        this.mRegistrationState = registrationState;
    }

    /** Gets the name of the DiscoveredDevice device. */
    @Nullable
    public String getName() {
        return mName;
    }

    /** Returns registration state of the device. */
    public @RegistrationState int getRegistrationState() {
        return mRegistrationState;
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
        sb.append("registered=").append(mRegistrationState);
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof DiscoveredDevice) {
            DiscoveredDevice otherDevice = (DiscoveredDevice) other;
            return Objects.equals(this.mName, otherDevice.mName)
                    && this.getRegistrationState() == otherDevice.getRegistrationState();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mRegistrationState);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        String name = getName();
        dest.writeString(name);
        dest.writeInt(getRegistrationState());
    }

    /**
     * Builder for {@link DiscoveredDevice} objects.
     *
     * @hide
     */
    public static final class Builder {
        @Nullable private String mName;
        // represents if device is already registered
        private @RegistrationState int mRegistrationState;

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
         * @param registrationState of the DiscoveredDevice.
         */
        @NonNull
        public DiscoveredDevice.Builder setRegistrationState(
                @RegistrationState int registrationState) {
            this.mRegistrationState = registrationState;
            return this;
        }

        /**
         * Creates the {@link DiscoveredDevice} instance.
         *
         * @return the configured {@link DiscoveredDevice} instance.
         */
        @NonNull
        public DiscoveredDevice build() {
            return new DiscoveredDevice(mName, mRegistrationState);
        }
    }
}
