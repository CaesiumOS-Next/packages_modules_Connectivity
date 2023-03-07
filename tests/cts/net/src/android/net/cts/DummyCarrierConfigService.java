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

package android.net.cts;

import android.content.Intent;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.service.carrier.CarrierIdentifier;
import android.service.carrier.CarrierService;

import java.util.concurrent.TimeoutException;

public class DummyCarrierConfigService extends CarrierService {
    private static final String TAG = "DummyCarrierConfigService";

    private static final Object sIsBoundLock = new Object();
    private static boolean sIsBound = false;

    public DummyCarrierConfigService() {}

    @Override
    public PersistableBundle onLoadConfig(CarrierIdentifier id) {
        return new PersistableBundle(); // Do nothing
    }

    @Override
    public IBinder onBind(Intent intent) {
        synchronized (sIsBoundLock) {
            sIsBound = true;
            sIsBoundLock.notifyAll();
        }

        return super.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        synchronized (sIsBoundLock) {
            sIsBound = false;
            sIsBoundLock.notifyAll();
        }

        return super.onUnbind(intent);
    }

    public static void awaitBind(long timeoutMs) throws TimeoutException, InterruptedException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() < (startTime + timeoutMs)) {
            synchronized (sIsBoundLock) {
                if (sIsBound) {
                    return;
                }

                long waitTime = (startTime + timeoutMs) - System.currentTimeMillis();
                if (waitTime < 0) {
                    break;
                }
                sIsBoundLock.wait(waitTime);
            }
        }
        throw new TimeoutException("Failed to bind to CarrierService in " + timeoutMs + "ms");
    }

    public static void awaitUnbind(long timeoutMs) throws TimeoutException, InterruptedException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() < (startTime + timeoutMs)) {
            synchronized (sIsBoundLock) {
                if (!sIsBound) {
                    return;
                }

                long waitTime = (startTime + timeoutMs) - System.currentTimeMillis();
                if (waitTime < 0) {
                    break;
                }
                sIsBoundLock.wait(waitTime);
            }
        }

        throw new TimeoutException("Failed to unbind from CarrierService in " + timeoutMs + "ms");
    }
}
