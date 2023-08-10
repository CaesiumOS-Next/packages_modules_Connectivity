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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Registered remote authenticator.
 *
 * @hide
 */
// TODO(b/290092977): Add back after M-2023-11 release - @SystemApi(client = MODULE_LIBRARIES)
public final class RemoteAuthenticator implements Parcelable {
    /** ID of the registered as remote authenticator. */
    private final int mId;

    /** User that registered remote authenticator. */
    private final @NonNull UserHandle mUser;

    /** Name of the remote authenticator. */
    private final @NonNull String mName;

    private RemoteAuthenticator(int id, @NonNull UserHandle user, @NonNull String name) {
        this.mId = id;
        this.mUser = user;
        this.mName = name;
    }

    /** Returns the id of the {@link RemoteAuthenticator}. */
    public int getId() {
        return mId;
    }

    /** Returns the user that registered {@link RemoteAuthenticator}. */
    @NonNull
    public UserHandle getForUser() {
        return mUser;
    }

    /** Returns the name of the {@link RemoteAuthenticator}. */
    @Nullable
    public String getName() {
        return mName;
    }

    public static final @NonNull Creator<RemoteAuthenticator> CREATOR =
            new Creator<>() {
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
        @NonNull private UserHandle mUser;
        @Nullable private String mName;

        private Builder() {}

        public Builder(final int id, final UserHandle user) {
            this.mId = id;
            this.mUser = user;
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
         * Sets user of the {@link RemoteAuthenticator} instance.
         *
         * @param user {@link UserHandle} of the remote authenticator
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
        public RemoteAuthenticator.Builder setName(@Nullable String name) {
            this.mName = name;
            return this;
        }

        /**
         * Creates the {@link RemoteAuthenticator} instance.
         *
         * @return the configured {@link RemoteAuthenticator} instance.
         */
        @NonNull
        public RemoteAuthenticator build() {
            return new RemoteAuthenticator(mId, mUser, mName);
        }
    }
}
