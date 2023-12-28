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

import android.annotation.NonNull;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.thread.openthread.DnsTxtAttribute;
import com.android.server.thread.openthread.INsdPublisher;
import com.android.server.thread.openthread.INsdStatusReceiver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Implementation of {@link INsdPublisher}.
 *
 * <p>This class provides API for service registration and discovery over mDNS. This class is a
 * proxy between ot-daemon and NsdManager.
 *
 * <p>All the data members of this class MUST be accessed in the {@code mHandler}'s Thread except
 * {@code mHandler} itself.
 */
public final class NsdPublisher extends INsdPublisher.Stub {
    // TODO: b/321883491 - specify network for mDNS operations
    private static final String TAG = NsdPublisher.class.getSimpleName();
    private final NsdManager mNsdManager;
    private final Handler mHandler;
    private final SparseArray<RegistrationListener> mRegistrationListeners = new SparseArray<>(0);

    /**
     * Guard the {@link NsdManager} operations.
     *
     * <p>Only one NsdManager operation is allowed at the same time. Before doing an operation, call
     * {@link #acquire()}. When the operation is complete (i.e. there's a callback called on the
     * listener), call {@link #release()}.
     */
    private final Semaphore mSemaphore = new Semaphore(1);

    @VisibleForTesting
    public NsdPublisher(NsdManager nsdManager, Handler handler) {
        mNsdManager = nsdManager;
        mHandler = handler;
    }

    public static NsdPublisher newInstance(Context context, Handler handler) {
        return new NsdPublisher(context.getSystemService(NsdManager.class), handler);
    }

    @Override
    public void registerService(
            String hostname,
            String name,
            String type,
            List<String> subTypeList,
            int port,
            List<DnsTxtAttribute> txt,
            INsdStatusReceiver receiver,
            int listenerId) {
        mHandler.post(
                () -> {
                    registerServiceInternal(
                            hostname, name, type, subTypeList, port, txt, receiver, listenerId);
                });
    }

    private void registerServiceInternal(
            String hostname,
            String name,
            String type,
            List<String> subTypeList,
            int port,
            List<DnsTxtAttribute> txt,
            INsdStatusReceiver receiver,
            int listenerId) {
        checkOnHandlerThread();
        acquire();
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(name);
        serviceInfo.setHostname(hostname);
        serviceInfo.setServiceType(type);
        serviceInfo.setPort(port);
        serviceInfo.setSubtypes(new HashSet<>(subTypeList));
        for (DnsTxtAttribute attribute : txt) {
            serviceInfo.setAttribute(attribute.name, attribute.value);
        }
        Log.i(
                TAG,
                "Registering service."
                        + "Listener ID: "
                        + listenerId
                        + ", serviceInfo: "
                        + serviceInfo);

        RegistrationListener listener = new RegistrationListener(listenerId, receiver);
        mRegistrationListeners.append(listenerId, listener);
        mNsdManager.registerService(serviceInfo, PROTOCOL_DNS_SD, listener);
    }

    @Override
    public void unregisterService(INsdStatusReceiver receiver, int listenerId) {
        mHandler.post(() -> unregisterServiceInternal(receiver, listenerId));
    }

    public void unregisterServiceInternal(INsdStatusReceiver receiver, int listenerId) {
        checkOnHandlerThread();
        acquire();
        RegistrationListener registrationListener = mRegistrationListeners.get(listenerId);
        if (registrationListener != null) {
            registrationListener.addUnregistrationReceiver(receiver);
            Log.i(TAG, "Unregistering service. Listener ID: " + listenerId);
            mNsdManager.unregisterService(registrationListener);
        } else {
            Log.w(
                    TAG,
                    "Failed to unregister service (id = "
                            + listenerId
                            + "): the registrationListener is empty.");
        }
    }

    private void checkOnHandlerThread() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            Log.wtf(
                    TAG,
                    "The task is not running on the Handler's Thread "
                            + mHandler.getLooper()
                            + " but on "
                            + Looper.myLooper());
        }
    }

    /** On ot-daemon died, unregister all registrations. */
    public void onOtDaemonDied() {
        checkOnHandlerThread();
        for (int i = 0; i < mRegistrationListeners.size(); ++i) {
            mNsdManager.unregisterService(mRegistrationListeners.valueAt(i));
        }
        mRegistrationListeners.clear();
    }

    private final class RegistrationListener implements NsdManager.RegistrationListener {
        private final int mListenerId;
        private final INsdStatusReceiver mRegistrationReceiver;
        private final List<INsdStatusReceiver> mUnregistrationReceivers;
        private boolean mRegistrationCompleted = false;
        private boolean mUnregistrationCompleted = false;

        RegistrationListener(int listenerId, @NonNull INsdStatusReceiver registrationReceiver) {
            mListenerId = listenerId;
            mRegistrationReceiver = registrationReceiver;
            mUnregistrationReceivers = new ArrayList<>();
        }

        void addUnregistrationReceiver(@NonNull INsdStatusReceiver unregistrationReceiver) {
            mUnregistrationReceivers.add(unregistrationReceiver);
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            if (!mRegistrationCompleted) {
                release();
                mHandler.post(() -> onRegistrationFailedInternal(serviceInfo, errorCode));
                mRegistrationCompleted = true;
            }
        }

        private void onRegistrationFailedInternal(NsdServiceInfo serviceInfo, int errorCode) {
            checkOnHandlerThread();
            Log.i(
                    TAG,
                    "Failed to register listener ID: "
                            + mListenerId
                            + " serviceInfo: "
                            + serviceInfo
                            + " error code: "
                            + errorCode);
            try {
                mRegistrationReceiver.onError(errorCode);
            } catch (RemoteException ignored) {
                // do nothing if the client is dead
            }
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            if (!mUnregistrationCompleted) {
                release();
                mHandler.post(() -> onUnregistrationFailedInternal(serviceInfo, errorCode));
                mUnregistrationCompleted = true;
            }
        }

        public void onUnregistrationFailedInternal(NsdServiceInfo serviceInfo, int errorCode) {
            checkOnHandlerThread();
            for (INsdStatusReceiver receiver : mUnregistrationReceivers) {
                Log.i(
                        TAG,
                        "Failed to unregister."
                                + "Listener ID: "
                                + mListenerId
                                + ", error code: "
                                + errorCode
                                + ", serviceInfo: "
                                + serviceInfo);
                try {
                    receiver.onError(errorCode);
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            if (!mRegistrationCompleted) {
                release();
                mHandler.post(() -> onServiceRegisteredInternal(serviceInfo));
                mRegistrationCompleted = true;
            }
        }

        private void onServiceRegisteredInternal(NsdServiceInfo serviceInfo) {
            checkOnHandlerThread();
            Log.i(
                    TAG,
                    "Registered successfully. "
                            + "Listener ID: "
                            + mListenerId
                            + ", serviceInfo: "
                            + serviceInfo);
            try {
                mRegistrationReceiver.onSuccess();
            } catch (RemoteException ignored) {
                // do nothing if the client is dead
            }
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            if (!mUnregistrationCompleted) {
                release();
                mHandler.post(() -> onServiceUnregisteredInternal(serviceInfo));
                mUnregistrationCompleted = true;
            }
        }

        public void onServiceUnregisteredInternal(NsdServiceInfo serviceInfo) {
            checkOnHandlerThread();
            for (INsdStatusReceiver receiver : mUnregistrationReceivers) {
                Log.i(
                        TAG,
                        "Unregistered successfully."
                                + "Listener ID: "
                                + mListenerId
                                + ", serviceInfo: "
                                + serviceInfo);
                try {
                    receiver.onSuccess();
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
            mRegistrationListeners.remove(mListenerId);
        }
    }

    void acquire() {
        mSemaphore.acquireUninterruptibly(1);
    }

    void release() {
        mSemaphore.release(1);
    }
}
