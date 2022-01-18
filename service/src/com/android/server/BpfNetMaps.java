/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import android.os.ServiceSpecificException;
import android.util.Log;

/**
 * BpfNetMaps is responsible for providing traffic controller relevant functionality.
 *
 * {@hide}
 */
public class BpfNetMaps {
    private static final String TAG = "BpfNetMaps";
    private final INetd mNetd;
  
    BpfNetMaps(INetd netd) {      
        mNetd = netd;
    }

    /**
     * Add naughty app
     */
    public void addNaughtyApp(final int uid) throws ServiceSpecificException {
        if (isAtLeastT()) {
             mNetd.addNaughtyApp();
             return;
        }
          
        final int err = native_addNaughtyApp(uid);
        if (err != 0) {
            Log.e(TAG, "addNaughtyApp failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to add naughty app");
        }
    }


    private native int native_addNaughtyApp(int uid) throws ServiceSpecificException;
}
