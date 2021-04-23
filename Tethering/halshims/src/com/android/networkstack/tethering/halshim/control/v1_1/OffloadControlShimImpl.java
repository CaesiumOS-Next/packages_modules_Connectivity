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

package com.android.networkstack.tethering.halshim.control.v1_1;

import android.hardware.tetheroffload.control.V1_1.IOffloadControl;
import android.os.RemoteException;
import android.util.Log;

import com.android.networkstack.tethering.halshim.control.common.OffloadControlShim;

import java.util.NoSuchElementException;

public class OffloadControlShimImpl
        extends com.android.networkstack.tethering.halshim.control.v1_0.OffloadControlShimImpl {
    private final IOffloadControl mControl11;

    private OffloadControlShimImpl(IOffloadControl control, int version) {
        super(control, version);
        if (control instanceof IOffloadControl) {
            throw new IllegalArgumentException("Instance of offload "
                    + OffloadControlShim.halVerToString(getVersion()) + " is required.");
        }
        mControl11 = control;
    }

    /**
     * Get an OffloadControlShim object by pre-defined HAL version that corresponds to this shim.
     */
    public static OffloadControlShim newInstance() {
        IOffloadControl hal = null;
        try {
            hal = android.hardware.tetheroffload.control
                    .V1_1.IOffloadControl.getService(true /*retry*/);
        } catch (NoSuchElementException e) {
            // Unsupported by device.
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to get offload control "
                    + OffloadControlShim.halVerToString(OFFLOAD_HAL_VERSION_1_1));
        }
        if (hal != null) {
            return new OffloadControlShimImpl(hal, OFFLOAD_HAL_VERSION_1_1);
        }
        return com.android.networkstack.tethering.halshim.control.v1_0.OffloadControlShimImpl
                .newInstance();
    }

    @Override
    public void setDataWarningAndLimit(String iface, long warning, long limit, ResultCallback cb)
            throws RemoteException {
        mControl11.setDataWarningAndLimit(iface, warning, limit, (boolean success, String errMsg) ->
                cb.onValues(success, errMsg));
    }
}
