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

package android.net.netstats;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Various utilities used for NetworkStats related code.
 *
 * @hide
 */
public class TrafficStatsConfig implements Parcelable {
    private final boolean mAlwaysUseTrafficStatsRateLimitCache;
    private final int mTrafficStatsRateLimitCacheExpiryDuration;
    private final int mTrafficStatsRateLimitCacheMaxEntries;

    public TrafficStatsConfig(boolean a, int b, int c) {
        mAlwaysUseTrafficStatsRateLimitCache = a;
        mTrafficStatsRateLimitCacheExpiryDuration = b;
        mTrafficStatsRateLimitCacheMaxEntries = c;
    }

    protected TrafficStatsConfig(Parcel in) {
        mAlwaysUseTrafficStatsRateLimitCache = in.readBoolean();
        mTrafficStatsRateLimitCacheExpiryDuration = in.readInt();
        mTrafficStatsRateLimitCacheMaxEntries = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mAlwaysUseTrafficStatsRateLimitCache);
        dest.writeInt(mTrafficStatsRateLimitCacheExpiryDuration);
        dest.writeInt(mTrafficStatsRateLimitCacheMaxEntries);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<TrafficStatsConfig> CREATOR = new Creator<TrafficStatsConfig>() {
        @Override
        public TrafficStatsConfig createFromParcel(Parcel in) {
            return new TrafficStatsConfig(in);
        }

        @Override
        public TrafficStatsConfig[] newArray(int size) {
            return new TrafficStatsConfig[size];
        }
    };

    public int getTrafficStatsRateLimitCacheExpiryDuration() {
        return mTrafficStatsRateLimitCacheExpiryDuration;
    }
    public int getTrafficStatsRateLimitCacheMaxEntries() {
        return mTrafficStatsRateLimitCacheMaxEntries;
    }
    public boolean isAlwaysUseTrafficStatsRateLimitCache() {
        return mAlwaysUseTrafficStatsRateLimitCache;
    }
}
