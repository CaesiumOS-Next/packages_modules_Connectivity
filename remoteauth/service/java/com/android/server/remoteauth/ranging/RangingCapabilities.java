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

import java.util.ArrayList;
import java.util.List;

/** Describes device ranging capabilities. */
public class RangingCapabilities {

    /** Ranging method */
    public enum RangingMethod {
        /** Unknown ranging method. */
        UNKNOWN,

        /** Ultra-wideband ranging. */
        UWB
    }

    private List<RangingMethod> mSupportedRangingMethods;

    public List<RangingMethod> getSupportedRangingMethods() {
        return mSupportedRangingMethods;
    }

    private RangingCapabilities(List<RangingMethod> supportedRangingMethods) {
        mSupportedRangingMethods = supportedRangingMethods;
    }

    /** Builder class for {@link RangingCapabilities}. */
    public static final class Builder {
        private List<RangingMethod> mSupportedRangingMethods = new ArrayList<>();

        /** Adds a supported {@link RangingMethod} */
        public Builder addSupportedRangingMethods(RangingMethod rangingMethod) {
            mSupportedRangingMethods.add(rangingMethod);
            return this;
        }

        /** Builds {@link RangingCapabilities}. */
        public RangingCapabilities build() {
            return new RangingCapabilities(mSupportedRangingMethods);
        }
    }
}
