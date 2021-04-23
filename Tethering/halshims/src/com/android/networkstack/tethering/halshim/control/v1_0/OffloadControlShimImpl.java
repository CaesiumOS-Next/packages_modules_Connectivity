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

package com.android.networkstack.tethering.halshim.control.v1_0;

import android.hardware.tetheroffload.control.V1_0.IOffloadControl;
import android.os.RemoteException;
import android.util.Log;

import com.android.networkstack.tethering.halshim.control.common.OffloadControlShim;

import java.util.ArrayList;
import java.util.NoSuchElementException;

public class OffloadControlShimImpl implements OffloadControlShim {
    private final IOffloadControl mControl;
    private final int mVersion;

    protected OffloadControlShimImpl(IOffloadControl control, int version) {
        mControl = control;
        mVersion = version;
    }

    @Override
    public int getVersion() {
        return mVersion;
    }

    /**
     * Get an OffloadControlShim object by pre-defined HAL version that corresponds to this shim.
     */
    public static OffloadControlShim newInstance() {
        IOffloadControl hal = null;
        try {
            hal = android.hardware.tetheroffload.control
                    .V1_0.IOffloadControl.getService(true /*retry*/);
        }  catch (NoSuchElementException e) {
            // Unsupported by device.
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to get offload control "
                    + OffloadControlShim.halVerToString(OFFLOAD_HAL_VERSION_1_0));
        }
        if (hal != null) {
            return new OffloadControlShimImpl(hal, OFFLOAD_HAL_VERSION_1_0);
        }
        return null;
    }

    @Override
    public void initOffload(OffloadCallbackShimImpl iTetheringOffloadCallback, ResultCallback cb)
            throws RemoteException {
        mControl.initOffload(iTetheringOffloadCallback.getCallback(),
                (boolean success, String errMsg) -> cb.onValues(success, errMsg));
    }

    @Override
    public void stopOffload(ResultCallback cb) throws RemoteException {
        mControl.stopOffload((boolean success, String errMsg) -> cb.onValues(success, errMsg));
    }

    @Override
    public void setLocalPrefixes(ArrayList<String> arrayList, ResultCallback cb)
            throws RemoteException {
        mControl.setLocalPrefixes(arrayList, (boolean success, String errMsg) ->
                cb.onValues(success, errMsg));
    }

    @Override
    public void getForwardedStats(String s, ForwardedStatsCallback cb)
            throws RemoteException {
        mControl.getForwardedStats(s, (long rxBytes, long txBytes) ->
                cb.onValues(rxBytes, txBytes));
    }

    @Override
    public void setDataLimit(String s, long l, ResultCallback cb) throws RemoteException {
        mControl.setDataLimit(s, l, (boolean success, String errMsg) ->
                cb.onValues(success, errMsg));
    }

    @Override
    public void setUpstreamParameters(String s, String s1, String s2, ArrayList<String> arrayList,
            ResultCallback cb) throws RemoteException {
        mControl.setUpstreamParameters(s, s1, s2, arrayList, (boolean success, String errMsg) ->
                cb.onValues(success, errMsg));
    }

    @Override
    public void addDownstream(String s, String s1, ResultCallback cb) throws RemoteException {
        mControl.addDownstream(s, s1, (boolean success, String errMsg) ->
                cb.onValues(success, errMsg));
    }

    @Override
    public void removeDownstream(String s, String s1, ResultCallback cb) throws RemoteException {
        mControl.removeDownstream(s, s1, (boolean success, String errMsg) ->
                cb.onValues(success, errMsg));
    }
}
