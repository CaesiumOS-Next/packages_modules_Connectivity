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

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A callback for the Tethering Entitlement result.
 *
 * The callback will only invoke {@link #onTetheringEntitlemntResult},
 * when the Tethering entitlement check get the result back.
 */
public interface TetheringEntitlementResultCallback {

    /**
     * A key of Extra value to save result code of tethering entitlement.
     */
    String EXTRA_TETHERING_ENTITLEMENT_RESULT_CODE =
            "android.net.extra.TETHERING_ENTITLEMENT_RESULT_CODE";
    /**
     * A key of Extra value to save carrier content of tethering entitlement.
     */
    String EXTRA_TETHERING_ENTITLEMENT_DIALOG_CONTENT =
            "android.net.extra.TETHERING_ENTITLEMENT_DIALOG_CONTENT";

    /**
     * An unknown error occurred.
     */
    int RESULT_CODE_UNKNOWN_ERROR = 1111;

    /**
     * Tethering entitlement check got a successful result back.
     */
    int RESULT_CODE_SUCCESS = 1001;

    /**
     * Tethering entitlement check got a failed result back.
     */
    int RESULT_CODE_FAIL = 1002;

    /**
     * Device has network error occurred, and can not do tethering entitelment check.
     */
    int RESULT_CODE_NETWORK_ERROR = 1003;

    /**
     *  Network is good, but entitlement server has no response due to some reasons.
     */
    int RESULT_CODE_TIMEOUT = 1004;

    /** @hide */
    @IntDef(prefix = { "RESULT_CODE_" }, value = {
            RESULT_CODE_UNKNOWN_ERROR,
            RESULT_CODE_SUCCESS,
            RESULT_CODE_NETWORK_ERROR,
            RESULT_CODE_TIMEOUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ResultCode {}

    /**
     * Sends back the result of Tethering Entitlement.
     *
     * @param resultCode {@code ResultCode} The Tethering Entitlement result.
     * @param dialogContent Shows content of the carrier Tethering Entitlement message on dialog.
     */
    void onTetheringEntitlemntResult(int resultCode, @NonNull CharSequence dialogContent);
}
