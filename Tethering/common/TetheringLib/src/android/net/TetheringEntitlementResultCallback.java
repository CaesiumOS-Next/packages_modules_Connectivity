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

import android.app.Activity;

/**
 * A callback for the Tethering Entitlement result.
 */
public interface TetheringEntitlementResultCallback {

    /**
     * A key of Extra value to save result code of tethering entitlement.
     */
    String EXTRA_TETHERING_ENTITLEMENT_RESULT_CODE =
            "android.net.extra.TETHERING_ENTITLEMENT_RESULT_CODE";

   /**
     * Sends back the result of Tethering Entitlement.
     *
     * @param resultCode {@link Activity#RESULT_OK} to enable tethering,
     *                   or {@link Activity#RESULT_CANCELED} not to.
     */
    void onTetheringEntitlemntResult(int resultCode);
}
