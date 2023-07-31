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
package com.android.server.remote_auth.ranging;

/** Holds ranging report data. */
public class RangingReport {

    /** State of the proximity based on defined near and far boundaries. */
    public enum ProximityState {
        /** Unknown proximity state. */
        UNKNOWN,

        /**
         * Proximity is inside the lower and upper proximity boundary. lowerProximityBoundaryM <=
         * proximity <= upperProximityBoundaryM
         */
        INSIDE,

        /**
         * Proximity is outside the lower and upper proximity boundary. proximity <
         * lowerProximityBoundaryM OR upperProximityBoundaryM < proximity
         */
        OUTSIDE
    }

    private float mDistanceM;
    private ProximityState mProximityState;

    public float getDistanceM() {
        return mDistanceM;
    }

    public ProximityState getProximityState() {
        return mProximityState;
    }

    private RangingReport(float distanceM, ProximityState proximityState) {
        mDistanceM = distanceM;
        mProximityState = proximityState;
    }

    /** Builder class for {@link RangingReport}. */
    public static final class Builder {
        private float mDistanceM;
        private ProximityState mProximityState;

        /** Set the distance in meters. */
        public Builder setDistanceM(float distanceM) {
            mDistanceM = distanceM;
            return this;
        }

        /** Set the proximity state. */
        public Builder setProximityState(ProximityState proximityState) {
            mProximityState = proximityState;
            return this;
        }

        /** Builds {@link RangingReport}. */
        public RangingReport build() {
            return new RangingReport(mDistanceM, mProximityState);
        }
    }
}
