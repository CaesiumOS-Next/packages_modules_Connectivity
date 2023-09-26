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

package com.android.server.thread;

import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;

<<<<<<< HEAD
=======
import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityFrameworkInitializerTiramisu;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.StubNetworkInfo;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.BorderRouterConfiguration;
import android.net.thread.IOperationReceiver;
import android.net.thread.IOperationalDatasetCallback;
import android.net.thread.IScheduleMigrationReceiver;
import android.net.thread.IStateCallback;
>>>>>>> 4429b9ef9b ([Don't reivew] PoC code of Thread unicast border routing)
import android.net.thread.IThreadNetworkController;
import android.net.thread.ThreadNetworkController;
<<<<<<< HEAD

/** Implementation of the {@link ThreadNetworkController} API. */
public final class ThreadNetworkControllerService extends IThreadNetworkController.Stub {
=======
import android.net.thread.ThreadNetworkController.DeviceRole;
import android.net.thread.ThreadNetworkException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.thread.openthread.IOtDaemon;
import com.android.server.thread.openthread.IOtDaemonCallback;
import com.android.server.thread.openthread.IOtStatusReceiver;
import com.android.server.thread.openthread.Ipv6AddressInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Implementation of the {@link IThreadNetworkController} API. */
public final class ThreadNetworkControllerService extends IThreadNetworkController.Stub {
    private static final String TAG = "ThreadNetworkService";
    private static final String THREAD_TUN_IF_NAME = IOtDaemon.TUN_IF_NAME;
    private static final int MTU = TunInterfaceController.MTU;
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SCHEDULE_MIGRATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DETACH_TIMEOUT = Duration.ofMillis(1500);

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final NetworkProvider mNetworkProvider;
    private final ConnectivityManager mConnectivityManager;
    private final TunInterfaceController mTunIfController;
    private InfraInterfaceController mInfraIfController;
    private final LinkProperties mLinkProperties = new LinkProperties();
    private final OtDaemonCallbackProxy mOtDaemonCallbackProxy = new OtDaemonCallbackProxy();
    private final List<AttachOperation> mOngoingAttachOperations = new LinkedList<>();
    private final List<ScheduleMigrationOperation> mOngoingMigrationOperations = new LinkedList<>();
    private final List<OutcomeReceiver<Void, ThreadNetworkException>> mOngoingDetachReceivers =
            new LinkedList<>();
    private IOtDaemon mOtDaemon;
    private NetworkAgent mNetworkAgent;
    private volatile Network mNetwork;
    private volatile BorderRouterConfiguration mBorderRouterConfig;
    private volatile ActiveOperationalDataset mActiveOpDataset;
    private volatile PendingOperationalDataset mPendingOpDataset;
    private volatile int mDeviceRole;

    @VisibleForTesting
    ThreadNetworkControllerService(
            Context context,
            HandlerThread handlerThread,
            NetworkProvider networkProvider,
            IOtDaemon otDaemon,
            ConnectivityManager connectivityManager,
            TunInterfaceController tunIfController,
            ScheduledExecutorService executor) {
        mContext = context;
        mHandlerThread = handlerThread;
        mHandler = new Handler(handlerThread.getLooper());
        mNetworkProvider = networkProvider;
        mOtDaemon = otDaemon;
        mConnectivityManager = connectivityManager;
        mTunIfController = tunIfController;
    }

    public static ThreadNetworkControllerService newInstance(Context context) {
        HandlerThread handlerThread = new HandlerThread("ThreadHandlerThread");
        handlerThread.start();
        NetworkProvider networkProvider =
                new NetworkProvider(context, handlerThread.getLooper(), "ThreadNetworkProvider");

        return new ThreadNetworkControllerService(
                context,
                handlerThread,
                networkProvider,
                /* otDaemon */ null,
                context.getSystemService(ConnectivityManager.class),
                new TunInterfaceController(THREAD_TUN_IF_NAME),
                Executors.newSingleThreadScheduledExecutor());
    }

    private static NetworkCapabilities newNetworkCapabilities() {
        return new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .build();
    }

    private static InetAddress addressInfoToInetAddress(Ipv6AddressInfo addressInfo) {
        try {
            return InetAddress.getByAddress(addressInfo.address);
        } catch (UnknownHostException e) {
            // This is unlikely to happen unless the Thread daemon is critically broken
            return null;
        }
    }

    private static LinkAddress newLinkAddress(Ipv6AddressInfo addressInfo) {
        long deprecationTimeMillis =
                addressInfo.isPreferred
                        ? LinkAddress.LIFETIME_PERMANENT
                        : SystemClock.elapsedRealtime();

        InetAddress address = addressInfoToInetAddress(addressInfo);

        // flags and scope will be adjusted automatically depending on the address and
        // its lifetimes.
        return new LinkAddress(
                address,
                addressInfo.prefixLength,
                0 /* flags */,
                0 /* scope */,
                deprecationTimeMillis,
                LinkAddress.LIFETIME_PERMANENT /* expirationTime */);
    }

    private static String deviceRoleToString(@DeviceRole int deviceRole) {
        switch (deviceRole) {
            case DEVICE_ROLE_DISABLED:
                return "disabled";
            case DEVICE_ROLE_DETACHED:
                return "detached";
            case DEVICE_ROLE_CHILD:
                return "child";
            case DEVICE_ROLE_ROUTER:
                return "router";
            case DEVICE_ROLE_LEADER:
                return "leader";
            default:
                return "unknown";
        }
    }

    private void initializeOtDaemon() {
        try {
            mOtDaemon = IOtDaemon.Stub.asInterface(getOtDaemonBinder());
            mOtDaemon.initialize(mTunIfController.getTunFd(), mOtDaemonCallbackProxy);
            mOtDaemon.asBinder().linkToDeath(() -> mHandler.post(this::onOtDaemonDead), 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to initialize OT daemon", e);
            mOtDaemon = null;
        }
    }

    private void onOtDaemonDead() {
        Log.w(TAG, "OT daemon became dead, clean up...");
        mOtDaemonCallbackProxy.onOtDaemonBinderDeath();

        // For any reason the OT daemon process crashed, terminates all ongoing requests

        for (var detachCallback : mOngoingDetachReceivers) {
            detachCallback.onError(
                    new ThreadNetworkException(ERROR_INTERNAL_ERROR, "Thread stack error"));
        }
        mOngoingDetachReceivers.clear();

        for (AttachOperation attachOperation : mOngoingAttachOperations) {
            attachOperation.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
        mOngoingAttachOperations.clear();

        for (ScheduleMigrationOperation migrationOperation : mOngoingMigrationOperations) {
            migrationOperation.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
        mOngoingMigrationOperations.clear();

        mOtDaemon = null;
    }

    private static IBinder getOtDaemonBinder() throws RemoteException {
        IBinder binder =
                ConnectivityFrameworkInitializerTiramisu.getConnectivityServiceManager()
                        .getThreadDaemonServiceRegisterer()
                        .waitForService();

        // TODO: return it as ThreadNetworkException.UNAVAILABLE_ERROR
        if (binder == null) {
            throw new RemoteException("Internal error: failed to start OT daemon");
        }
        return binder;
    }

    public void initialize() {
        Log.d(TAG, "Initializing Thread system service...");
        try {
            mTunIfController.createTunInterface();
            Log.d(TAG, "Created Thread tun interface: " + THREAD_TUN_IF_NAME);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create Thread tunnel interface", e);
        }
        mLinkProperties.setInterfaceName(THREAD_TUN_IF_NAME);
        mLinkProperties.setMtu(MTU);
        mConnectivityManager.registerNetworkProvider(mNetworkProvider);

        initializeOtDaemon();

        mConnectivityManager.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        Log.i(TAG, "AIL is availalble");
                    }

                    @Override
                    public void onLost(@NonNull Network network) {
                        Log.i(TAG, "AIL is lost");
                        try {
                            mOtDaemon
                                    .onInfraInterfaceStateChanged(
                                            mInfraIfController.getInterfaceName(),
                                            false,
                                            ParcelFileDescriptor.adoptFd(-1));
                            if (mInfraIfController != null) {
                                mInfraIfController.destroyInfraInterfaceSocket();
                                mInfraIfController = null;
                            }
                        } catch (Exception e) {
                            Log.i(TAG, "exception: " + e.toString());
                        }
                    }

                    @Override
                    public void onLinkPropertiesChanged(
                            @NonNull Network network, @NonNull LinkProperties linkProperties) {
                        try {
                            Log.i(
                                    TAG,
                                    "AIL link properties changed: "
                                            + linkProperties.getInterfaceName());
                            if (mInfraIfController == null) {
                                final String infraIfName = linkProperties.getInterfaceName();

                                Log.i(TAG, "111111111111111111");

                                mInfraIfController = new InfraInterfaceController(infraIfName);
                                Log.i(TAG, "222222222222222222");
                                mInfraIfController.createInfraInterfaceSocket();
                                Log.i(TAG, "333333333333333333");
                                ParcelFileDescriptor sockFd = mInfraIfController.getSocketFd();
                                mOtDaemon
                                        .onInfraInterfaceStateChanged(infraIfName, true, sockFd);
                                Log.i(TAG, "444444444444444444");
                                mLinkProperties.setStubNetworkInfo(
                                        new StubNetworkInfo(infraIfName));
                                if (mNetworkAgent != null) {
                                    mNetworkAgent.sendLinkProperties(mLinkProperties);
                                }
                                Log.i(TAG, "555555555555555555");
                            }
                        } catch (Exception e) {
                            Log.i(TAG, "exception: " + e.toString());
                        }
                    }
                });
    }

    private void requestThreadNetwork() {
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .build();
        mConnectivityManager.requestNetwork(request, new ConnectivityManager.NetworkCallback());
    }

    private void registerThreadNetwork() {
        if (mNetworkAgent != null) {
            return;
        }
        NetworkCapabilities netCaps = newNetworkCapabilities();
        mNetworkAgent =
                newNetworkAgent(
                        mContext,
                        mHandlerThread.getLooper(),
                        TAG,
                        netCaps,
                        mLinkProperties,
                        new NetworkScore.Builder().build(),
                        new NetworkAgentConfig.Builder().build(),
                        mNetworkProvider);
        mNetwork = mNetworkAgent.register();
        mNetworkAgent.markConnected();
        requestThreadNetwork();
        Log.i(TAG, "Registered Thread network");
    }

    private void unregisterThreadNetwork() {
        if (mNetworkAgent == null) {
            return;
        }

        Log.d(TAG, "Unregistering Thread network agent");

        mNetworkAgent.unregister();
        mNetworkAgent = null;
        mNetwork = null;
    }

    private void updateTunInterfaceAddress(LinkAddress linkAddress, boolean isAdded) {
        try {
            if (isAdded) {
                mTunIfController.addAddress(linkAddress);
            } else {
                mTunIfController.removeAddress(linkAddress);
            }
        } catch (IOException e) {
            Log.e(
                    TAG,
                    String.format(
                            "Failed to %s Thread tun interface address %s",
                            (isAdded ? "add" : "remove"), linkAddress),
                    e);
        }
    }

    private void updateNetworkLinkProperties(LinkAddress linkAddress, boolean isAdded) {
        if (isAdded) {
            mLinkProperties.addLinkAddress(linkAddress);
        } else {
            mLinkProperties.removeLinkAddress(linkAddress);
        }

        if (mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(mLinkProperties);
        }
    }
>>>>>>> 4429b9ef9b ([Don't reivew] PoC code of Thread unicast border routing)

    @Override
    public int getThreadVersion() {
        return THREAD_VERSION_1_3;
    }
}
