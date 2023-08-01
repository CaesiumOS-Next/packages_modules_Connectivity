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

package android.remoteauth;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * A class represents a registered remote authenticator device.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class RemoteAuthenticator implements Parcelable {
    private final int mId; // id of the registered authenticator
    private final @NonNull UserHandle mUser; // id of the user that registered the authenticator
    private final @NonNull String mName; // name of the remote authenticator

    public RemoteAuthenticator(int id, @NonNull UserHandle user, @NonNull String name) {
        this.mId = id;
        this.mUser = user;
        this.mName = name;
    }

    public int getId() {
        return mId;
    }

    @NonNull
    public UserHandle getForUser() {
        return mUser;
    }

    @NonNull
    public String getName() {
        return mName;
    }

    public static final @NonNull Creator<RemoteAuthenticator> CREATOR = new Creator<>() {
        @Override
        public RemoteAuthenticator createFromParcel(Parcel in) {
            RemoteAuthenticator.Builder builder = new RemoteAuthenticator.Builder();
            builder.setId(in.readInt());
            builder.setForUser(UserHandle.of(in.readInt()));
            builder.setName(in.readString());

            return builder.build();
        }

        @Override
        public RemoteAuthenticator[] newArray(int size) {
            return new RemoteAuthenticator[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RemoteAuthenticator [");
        sb.append("id=").append(getId()).append(", ");
        sb.append("userId=").append(getForUser().getIdentifier()).append(", ");
        sb.append("name=").append(getName()).append(", ");
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof RemoteAuthenticator) {
            RemoteAuthenticator raOther = (RemoteAuthenticator) other;
            return getId() == raOther.getId()
                    && getForUser().getIdentifier() == raOther.getForUser().getIdentifier()
                    && Objects.equals(getName(), raOther.getName());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mUser.getIdentifier(), mName);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(getId());
        dest.writeInt(getForUser().getIdentifier());
        dest.writeString(getName());
    }

    /**
     * Builder for {@link RemoteAuthenticator} objects.
     *
     * @hide
     */
    public static final class Builder {
        private int mId;
        private UserHandle mUser;
        private String mName;

        public Builder() {
        }

        /**
         * Sets id of the {@link RemoteAuthenticator} instance.
         *
         * @param id of the remote authenticator
         * @return the builder of {@link RemoteAuthenticator}.
         */
        @NonNull
        public RemoteAuthenticator.Builder setId(int id) {
            this.mId = id;
            return this;
        }

        /**
         * Sets userId of the {@link RemoteAuthenticator} instance.
         *
         * @param user user handler of the remote authenticator
         * @return the builder of {@link RemoteAuthenticator}.
         */
        @NonNull
        public RemoteAuthenticator.Builder setForUser(@NonNull UserHandle user) {
            this.mUser = user;
            return this;
        }

        /**
         * Sets name of the {@link RemoteAuthenticator} instance.
         *
         * @param name of the remote authenticator
         * @return the builder of {@link RemoteAuthenticator}.
         */
        @NonNull
        public RemoteAuthenticator.Builder setName(@NonNull String name) {
            this.mName = name;
            return this;
        }

        /**
         * Creates the {@link RemoteAuthenticator} instance.
         * @return the configured {@link RemoteAuthenticator} instance.
         */
        @NonNull
        public RemoteAuthenticator build() {
            return new RemoteAuthenticator(mId, mUser, mName);
        }
    }
}
