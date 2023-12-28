/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.thread;

import static android.net.nsd.NsdManager.PROTOCOL_DNS_SD;

import android.annotation.Nullable;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.thread.openthread.DnsTxtAttribute;
import com.android.server.thread.openthread.IMdnsPublisher;
import com.android.server.thread.openthread.IMdnsStatusReceiver;
import com.android.server.thread.openthread.IOtDaemon;

import java.util.List;

public class MdnsPublisher extends IMdnsPublisher.Stub {
    private static final String TAG = MdnsPublisher.class.getSimpleName();
    private NsdManager mNsdManager;
    private Handler mHandler;
    private IOtDaemon mOtDaemon;
    private final SparseArray<RegistrationListener> mMdnsRegistrationListeners =
            new SparseArray<>(0);

    public MdnsPublisher(NsdManager nsdManager, Handler handler, IOtDaemon otDaemon) {
        mNsdManager = nsdManager;
        mHandler = handler;
        mOtDaemon = otDaemon;
    }

    public static MdnsPublisher newInstance(Context context, Handler handler, IOtDaemon otDaemon) {
        return new MdnsPublisher(context.getSystemService(NsdManager.class), handler, otDaemon);
    }

    @Override
    public void registerService(
            String hostName,
            String name,
            String type,
            List<String> subTypeList,
            int port,
            List<DnsTxtAttribute> txt,
            IMdnsStatusReceiver receiver,
            int listenerId) {
        checkOnHandlerThread();
        mHandler.post(
                () -> {
                    NsdServiceInfo serviceInfo = new NsdServiceInfo();
                    serviceInfo.setServiceName(name);
                    serviceInfo.setServiceType(type);
                    serviceInfo.setPort(port);
                    for (DnsTxtAttribute attribute : txt) {
                        serviceInfo.setAttribute(attribute.name, attribute.value);
                    }

                    RegistrationListener listener = new RegistrationListener(listenerId, receiver);
                    mMdnsRegistrationListeners.append(listenerId, listener);
                    mNsdManager.registerService(serviceInfo, PROTOCOL_DNS_SD, listener);
                });
    }

    @Override
    public void unregisterService(IMdnsStatusReceiver receiver, int listenerId) {
        checkOnHandlerThread();
        mHandler.post(
                () -> {
                    RegistrationListener registrationListener =
                            mMdnsRegistrationListeners.get(listenerId);
                    if (registrationListener != null) {
                        registrationListener.setUnregisterReceiver(receiver);
                        mNsdManager.unregisterService(registrationListener);
                    }
                });
    }

    private void checkOnHandlerThread() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            Log.wtf(TAG, "Must be on the handler thread!");
        }
    }

    private final class RegistrationListener implements NsdManager.RegistrationListener {
        private int mListenerId;
        private final IMdnsStatusReceiver mRegistrationReceiver;
        private @Nullable IMdnsStatusReceiver mUnregistrationReceiver;

        RegistrationListener(int listenerId, IMdnsStatusReceiver registrationReceiver) {
            mListenerId = listenerId;
            mRegistrationReceiver = registrationReceiver;
            mUnregistrationReceiver = null;
        }

        void setUnregisterReceiver(IMdnsStatusReceiver unregistrationReceiver) {
            mUnregistrationReceiver = unregistrationReceiver;
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            try {
                if (mRegistrationReceiver != null) {
                    mRegistrationReceiver.onError(errorCode);
                }
            } catch (RemoteException ignored) {
            }
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            try {
                if (mUnregistrationReceiver != null) {
                    mUnregistrationReceiver.onError(errorCode);
                }
            } catch (RemoteException ignored) {
            }
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            try {
                if (mRegistrationReceiver != null) {
                    mRegistrationReceiver.onSuccess();
                }
            } catch (RemoteException ignored) {
            }
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            try {
                if (mUnregistrationReceiver != null) {
                    mUnregistrationReceiver.onSuccess();
                }
                mMdnsRegistrationListeners.remove(mListenerId);
            } catch (RemoteException ignored) {
            }
        }
    }
}
