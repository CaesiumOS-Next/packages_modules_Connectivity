/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server.thread;

import static android.net.MulticastRoutingConfig.FORWARD_NONE;
import static android.net.MulticastRoutingConfig.FORWARD_SELECTED;
import static android.net.MulticastRoutingConfig.FORWARD_WITH_MIN_SCOPE;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_CHILD;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DETACHED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DISABLED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_ROUTER;
import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;
import static android.net.thread.ThreadNetworkException.ERROR_TIMEOUT;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityFrameworkInitializerTiramisu;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LocalNetworkConfig;
import android.net.MulticastRoutingConfig;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.BorderRouterConfiguration;
import android.net.thread.IOperationReceiver;
import android.net.thread.IOperationalDatasetCallback;
import android.net.thread.IScheduleMigrationReceiver;
import android.net.thread.IStateCallback;
import android.net.thread.IThreadNetworkController;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadNetworkController;
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
import java.net.Inet6Address;
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
    private final NetworkRequest mUpstreamNetworkRequest =
            new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
    private IOtDaemon mOtDaemon;
    private NetworkAgent mNetworkAgent;
    private volatile Network mNetwork;
    private volatile long mPartitionId;
    private volatile BorderRouterConfiguration mBorderRouterConfig;
    private volatile ActiveOperationalDataset mActiveOpDataset;
    private volatile PendingOperationalDataset mPendingOpDataset;
    private volatile int mDeviceRole;

    private MulticastRoutingConfig.Builder mUpstreamBuilder =
            new MulticastRoutingConfig.Builder(MulticastRoutingConfig.FORWARD_NONE);
    private MulticastRoutingConfig.Builder mDownstreamBuilder =
            new MulticastRoutingConfig.Builder(MulticastRoutingConfig.FORWARD_NONE);

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
                .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .build();
    }

    private static Inet6Address bytesToInet6Address(byte[] addressBytes) {
        try {
            return (Inet6Address) Inet6Address.getByAddress(addressBytes);
        } catch (UnknownHostException e) {
            // This is unlikely to happen unless the Thread daemon is critically broken
            return null;
        }
    }

    private static InetAddress addressInfoToInetAddress(Ipv6AddressInfo addressInfo) {
        return bytesToInet6Address(addressInfo.address);
    }

    private final Runnable mOtDaemonDeathRecipient =
            () -> {
                Log.w(TAG, "OT daemon service became dead, clean up...");
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
            };

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
            mOtDaemon.asBinder().linkToDeath(() -> mHandler.post(mOtDaemonDeathRecipient), 0);
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
                            mOtDaemon.onInfraInterfaceStateChanged(
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
                                //mLinkProperties.setStubNetworkInfo(
                                //        new StubNetworkInfo(infraIfName));
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
                        .removeForbiddenCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
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
                        new LocalNetworkConfig.Builder()
                                .setUpstreamSelector(mUpstreamNetworkRequest)
                                .build(),
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
            mLinkProperties.addRoute(
                    new RouteInfo(
                            new IpPrefix(linkAddress.getAddress(), 64),
                            null,
                            "thread-wpan",
                            RouteInfo.RTN_UNICAST,
                            1500));
        } else {
            mLinkProperties.removeLinkAddress(linkAddress);
        }

        if (mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(mLinkProperties);
        }
    }

    @Override
    public int getThreadVersion() {
        return THREAD_VERSION_1_3;
    }

    @Override
    @Nullable
    public ActiveOperationalDataset getActiveOperationalDataset() throws RemoteException {
        enforceCallingPermissionsGranted(
                permission.ACCESS_NETWORK_STATE, permission.THREAD_NETWORK_PRIVILEGED);
        return mActiveOpDataset;
    }

    @Override
    @Nullable
    public PendingOperationalDataset getPendingOperationalDataset() throws RemoteException {
        enforceCallingPermissionsGranted(
                permission.ACCESS_NETWORK_STATE, permission.THREAD_NETWORK_PRIVILEGED);
        return mPendingOpDataset;
    }

    private void enforceCallingPermissionsGranted(String... permissions) {
        for (String permission : permissions) {
            mContext.enforceCallingPermission(
                    permission, String.format("Permission %s is missing", permission));
        }
    }

    @Override
    public void registerStateCallback(IStateCallback stateCallback) throws RemoteException {
        enforceCallingPermissionsGranted(permission.ACCESS_NETWORK_STATE);

        mOtDaemonCallbackProxy.registerStateCallback(stateCallback);
    }

    @Override
    public void unregisterStateCallback(IStateCallback stateCallback) throws RemoteException {
        mOtDaemonCallbackProxy.unregisterStateCallback(stateCallback);
    }

    @Override
    public void registerOperationalDatasetCallback(IOperationalDatasetCallback callback)
            throws RemoteException {
        enforceCallingPermissionsGranted(
                permission.ACCESS_NETWORK_STATE, permission.THREAD_NETWORK_PRIVILEGED);
        mOtDaemonCallbackProxy.registerDatasetCallback(callback);
    }

    @Override
    public void unregisterOperationalDatasetCallback(IOperationalDatasetCallback callback)
            throws RemoteException {
        mOtDaemonCallbackProxy.unregisterDatasetCallback(callback);
    }

    private boolean isAttached() {
        return ThreadNetworkController.isAttached(mDeviceRole);
    }

    private boolean isDetaching() {
        return !mOngoingDetachReceivers.isEmpty();
    }

    @Override
    public void attach(
            boolean doForm,
            @NonNull ActiveOperationalDataset activeOpDataset,
            @NonNull IOperationReceiver receiver) {
        enforceCallingPermissionsGranted(permission.THREAD_NETWORK_PRIVILEGED);

        AttachOperation operation = new AttachOperation(doForm, activeOpDataset, receiver);
        mHandler.post(() -> attachInternal(operation));
    }

    private void attachInternal(AttachOperation operation) {
        mOngoingAttachOperations.add(operation);

        // Wait for the detach operation to complete before start attaching
        if (isAttached() || isDetaching()) {
            detachInternal(
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void ignored) {
                            attachFromDetached(operation);
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            if (mOngoingAttachOperations.remove(operation)) {
                                operation.onError(e.getErrorCode(), e.getMessage());
                            }
                        }
                    });
        } else {
            attachFromDetached(operation);
        }
    }

    private void attachFromDetached(AttachOperation operation) {
        try {
            mOtDaemon.attach(
                    operation.getDoForm(),
                    operation.getActiveOperationalDataset().toThreadTlvs(),
                    new IOtStatusReceiver.Stub() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "otDaemon.attach operation succeed");
                            // Continue waiting for the role becoming attached
                        }

                        @Override
                        public void onError(int error, String message) {
                            mHandler.post(
                                    () -> {
                                        if (mOngoingAttachOperations.remove(operation)) {
                                            operation.onError(
                                                    ERROR_INTERNAL_ERROR, "Thread stack error");
                                        }
                                    });
                        }
                    });
            mHandler.postDelayed(
                    () -> {
                        if (mOngoingAttachOperations.remove(operation)) {
                            operation.onError(
                                    ERROR_TIMEOUT,
                                    String.format(
                                            "It took more than %ss to attach",
                                            ATTACH_TIMEOUT.toSeconds()));
                        }
                    },
                    ATTACH_TIMEOUT.toMillis());
        } catch (RemoteException e) {
            Log.e(TAG, "otDaemon.attach failed", e);
            mOngoingAttachOperations.remove(operation);
            operation.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
    }

    @Override
    public void scheduleMigration(
            PendingOperationalDataset pendingOpDataset,
            @NonNull IScheduleMigrationReceiver receiver)
            throws RemoteException {
        enforceCallingPermissionsGranted(permission.THREAD_NETWORK_PRIVILEGED);
        mHandler.post(
                () ->
                        scheduleMigrationInternal(
                                new ScheduleMigrationOperation(pendingOpDataset, receiver)));
    }

    public void scheduleMigrationInternal(ScheduleMigrationOperation operation) {
        if (!isAttached()) {
            operation.onError(
                    ERROR_FAILED_PRECONDITION, "Cannot schedule network migration when detached");
        }

        mOngoingMigrationOperations.add(operation);

        try {
            mOtDaemon.scheduleMigration(
                    operation.getPendingOperationalDataset().toThreadTlvs(),
                    new IOtStatusReceiver.Stub() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "Scheduled network migration on OT daemon");
                        }

                        @Override
                        public void onError(int error, String message) {
                            mHandler.post(
                                    () -> {
                                        if (mOngoingMigrationOperations.remove(operation)) {
                                            operation.onError(
                                                    ERROR_INTERNAL_ERROR, "Thread stack error");
                                        }
                                    });
                        }
                    });
            mHandler.postDelayed(
                    () -> {
                        if (!operation.isScheduled()
                                && mOngoingMigrationOperations.remove(operation)) {
                            operation.onError(
                                    ERROR_TIMEOUT,
                                    String.format(
                                            "It took more than %ss to schedule network migration",
                                            SCHEDULE_MIGRATION_TIMEOUT.toSeconds()));
                        }
                    },
                    SCHEDULE_MIGRATION_TIMEOUT.toMillis());
        } catch (RemoteException e) {
            Log.e(TAG, "otDaemon.scheduleMigration failed", e);
            mOngoingMigrationOperations.remove(operation);
            operation.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
    }

    @Override
    public void detach(@NonNull IOperationReceiver receiver) throws RemoteException {
        enforceCallingPermissionsGranted(permission.THREAD_NETWORK_PRIVILEGED);

        mHandler.post(
                () -> {
                    for (AttachOperation attachOperation : mOngoingAttachOperations) {
                        attachOperation.onError(ERROR_ABORTED, "Detach is requested");
                    }
                    mOngoingAttachOperations.clear();

                    for (ScheduleMigrationOperation migrationOperation :
                            mOngoingMigrationOperations) {
                        migrationOperation.onError(ERROR_ABORTED, "Detach is requested");
                    }
                    mOngoingMigrationOperations.clear();

                    detachInternal(
                            new OutcomeReceiver<Void, ThreadNetworkException>() {
                                @Override
                                public void onResult(Void v) {
                                    try {
                                        receiver.onSuccess();
                                    } catch (RemoteException ignored) {
                                        // do nothing if the client is dead
                                    }
                                }

                                @Override
                                public void onError(ThreadNetworkException e) {
                                    try {
                                        receiver.onError(e.getErrorCode(), e.getMessage());
                                    } catch (RemoteException ignored) {
                                        // Do nothing if the client is dead
                                    }
                                }
                            });
                });
    }

    private void detachInternal(OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        if (!isAttached()) {
            receiver.onResult(null);
            return;
        }

        mOngoingDetachReceivers.add(receiver);

        try {
            mOtDaemon.detach(
                    new IOtStatusReceiver.Stub() {
                        @Override
                        public void onSuccess() {
                            // Instead of notifies clients the result of the detach operation,
                            // wait for the Device Role to be propagated from the OT stack so that
                            // the device role is guaranteed to be detached when the receiver is
                            // invoked.
                        }

                        @Override
                        public void onError(int error, String message) {
                            mHandler.post(
                                    () -> {
                                        if (mOngoingDetachReceivers.remove(receiver)) {
                                            receiver.onError(
                                                    new ThreadNetworkException(error, message));
                                        }
                                    });
                        }
                    });
            mHandler.postDelayed(
                    () -> {
                        if (mOngoingDetachReceivers.remove(receiver)) {
                            receiver.onError(
                                    new ThreadNetworkException(
                                            ERROR_INTERNAL_ERROR, "Thread stack error"));
                        }
                    },
                    DETACH_TIMEOUT.toMillis());
        } catch (RemoteException e) {
            // Oneway AIDL API should never throw?
            mOngoingDetachReceivers.remove(receiver);
            receiver.onError(
                    new ThreadNetworkException(ERROR_INTERNAL_ERROR, "Thread stack error"));
        }
    }

    @Override
    public int getDeviceRole() throws RemoteException {
        enforceCallingPermissionsGranted(permission.ACCESS_NETWORK_STATE);
        return mDeviceRole;
    }

    @Override
    @Nullable
    public Network getCurrentNetwork() throws RemoteException {
        return mNetwork;
    }

    @Override
    public long getPartitionId() throws RemoteException {
        enforceCallingPermissionsGranted(permission.ACCESS_NETWORK_STATE);
        return mPartitionId;
    }

    @Override
    public byte[] getExtendedMacAddress() throws RemoteException {
        enforceCallingPermissionsGranted(permission.ACCESS_NETWORK_STATE);
        return mOtDaemon.getExtendedMacAddress();
    }

    @Override
    public boolean isBorderRouterSupported() {
        return true;
    }

    @Override
    public void setBorderRouterConfiguration(
            @NonNull BorderRouterConfiguration borderRouterConfig,
            @NonNull IOperationReceiver receiver)
            throws RemoteException {
        enforceCallingPermissionsGranted(permission.THREAD_NETWORK_PRIVILEGED);

        mHandler.post(
                () -> {
                    mBorderRouterConfig = borderRouterConfig;
                    try {
                        // TODO(b/271789589): sets Border Router configurations to OT daemon
                        receiver.onError(ERROR_INTERNAL_ERROR, "Feature not supported");
                    } catch (RemoteException e) {
                        // Do nothing if the caller is dead
                    }
                });
    }

    @Override
    @NonNull
    public BorderRouterConfiguration getBorderRouterConfiguration() {
        enforceCallingPermissionsGranted(permission.ACCESS_NETWORK_STATE);

        return mBorderRouterConfig;
    }

    private NetworkAgent newNetworkAgent(
            @NonNull Context context,
            @NonNull Looper looper,
            @NonNull String logTag,
            @NonNull NetworkCapabilities nc,
            @NonNull LinkProperties lp,
            @NonNull LocalNetworkConfig lnc,
            @NonNull NetworkScore score,
            @NonNull NetworkAgentConfig config,
            @Nullable NetworkProvider provider) {
        return new NetworkAgent(context, looper, logTag, nc, lp, lnc, score, config, provider) {
            @Override
            public void onNetworkUnwanted() {
                if (mNetworkAgent == null) {
                    return;
                }
                // Maybe stop the Thread stack if we are not a Border Router?
                Log.d(TAG, "The Thread network is unwanted by the Connectivity Service");
            }
        };
    }

    private void handleThreadInterfaceStateChanged(boolean isUp) {
        try {
            mTunIfController.setInterfaceUp(isUp);
            Log.d(TAG, "Thread network interface becomes " + (isUp ? "up" : "down"));
        } catch (IOException e) {
            Log.e(TAG, "Failed to handle Thread interface state changes", e);
        }
    }

    private void handlePartitionIdChanged(long partitionId) {
        mPartitionId = partitionId;
    }

    private void handleDeviceRoleChanged(@DeviceRole int deviceRole) {
        if (deviceRole == mDeviceRole) {
            return;
        }

        Log.d(
                TAG,
                String.format(
                        "Thread state/role changed: %s -> %s",
                        deviceRoleToString(mDeviceRole), deviceRoleToString(deviceRole)));
        mDeviceRole = deviceRole;

        if (isAttached() && mActiveOpDataset != null) {
            for (AttachOperation attachOperation : mOngoingAttachOperations) {
                // We probably need to match only the Extended PAN ID and Network Key - There are
                // cases that another device changes the Active Operational Dataset at the same
                // time but they are still in the same network. And we should consider this attach
                // request successful.
                if (mActiveOpDataset.equals(attachOperation.getActiveOperationalDataset())) {
                    attachOperation.onSuccess();
                } else {
                    attachOperation.onError(
                            ERROR_ABORTED, "This device is attached to a different network");
                }
            }
            mOngoingAttachOperations.clear();
        }

        if (!isAttached()) {
            for (var detachCallback : mOngoingDetachReceivers) {
                detachCallback.onResult(null);
            }
            mOngoingDetachReceivers.clear();
        }

        if (isAttached()) {
            Log.d(TAG, "Attached from the Thread network");
            registerThreadNetwork();
        } else {
            Log.d(TAG, "Detached from the Thread network");
            unregisterThreadNetwork();
        }
    }

    private void handleActiveOperationalDatasetChanged(
            @Nullable ActiveOperationalDataset activeOpDataset) {
        if (!Objects.equals(activeOpDataset, mActiveOpDataset)) {
            Log.d(
                    TAG,
                    String.format(
                            "Active Operational Dataset updated: %s -> %s",
                            mActiveOpDataset, activeOpDataset));
            mActiveOpDataset = activeOpDataset;
        }

        if (mActiveOpDataset == null) {
            return;
        }

        if (!isAttached()) {
            return;
        }

        for (AttachOperation attachOperation : mOngoingAttachOperations) {
            // We probably need to match only the Extended PAN ID and Network Key - There are cases
            // that another device changes the Active Operational Dataset at the same time but they
            // are still in the same network. And we should consider this attach request successful
            if (mActiveOpDataset.equals(attachOperation.getActiveOperationalDataset())) {
                attachOperation.onSuccess();
            } else {
                attachOperation.onError(
                        ERROR_ABORTED, "This device is attached to a different network");
            }
        }
        mOngoingAttachOperations.clear();
    }

    private void handlePendingOperationalDatasetChanged(
            @Nullable PendingOperationalDataset pendingOpDataset) {
        if (!Objects.equals(pendingOpDataset, mPendingOpDataset)) {
            Log.d(
                    TAG,
                    String.format(
                            "Pending Operational Dataset updated: %s -> %s",
                            mPendingOpDataset, pendingOpDataset));
            mPendingOpDataset = pendingOpDataset;
        }

        if (!isAttached()) {
            return;
        }

        if (mPendingOpDataset != null) {
            Duration applyMigrationTimeout =
                    mPendingOpDataset.getDelayTimer().plus(SCHEDULE_MIGRATION_TIMEOUT);
            List<ScheduleMigrationOperation> abortedOperations = new LinkedList<>();
            for (ScheduleMigrationOperation migrationOperation : mOngoingMigrationOperations) {
                // Matches only the Active Operational Dataset because the OT stack may override
                // the Delay Timer to recommended min or default values
                if (mPendingOpDataset
                        .getActiveOperationalDataset()
                        .equals(migrationOperation.getActiveOperationalDataset())) {
                    migrationOperation.onScheduled(mPendingOpDataset.getDelayTimer());
                    mHandler.postDelayed(
                            () -> {
                                if (mOngoingMigrationOperations.remove(migrationOperation)) {
                                    migrationOperation.onError(
                                            ERROR_TIMEOUT,
                                            String.format(
                                                    "It took more than %ss to apply network"
                                                            + " migration",
                                                    applyMigrationTimeout.toSeconds()));
                                }
                            },
                            applyMigrationTimeout.toMillis());
                } else {
                    abortedOperations.add(migrationOperation);
                    migrationOperation.onError(
                            ERROR_ABORTED,
                            "Scheduled to migrate to a different network "
                                    + mPendingOpDataset.getActiveOperationalDataset());
                }
            }
            mOngoingMigrationOperations.removeAll(abortedOperations);
        } else {
            for (ScheduleMigrationOperation migrationOperation : mOngoingMigrationOperations) {
                if (!migrationOperation.isScheduled()) {
                    continue;
                }
                if (mActiveOpDataset.equals(migrationOperation.getActiveOperationalDataset())) {
                    migrationOperation.onMigrated();
                } else {
                    migrationOperation.onError(
                            ERROR_ABORTED, "Migrated to a different network " + mActiveOpDataset);
                }
            }
            mOngoingMigrationOperations.clear();
        }
    }

    private void handleAddressChanged(Ipv6AddressInfo addressInfo, boolean isAdded) {
        InetAddress address = addressInfoToInetAddress(addressInfo);
        if (address.isMulticastAddress()) {
            Log.i(TAG, "Ignoring multicast address " + address.getHostAddress());
            return;
        }

        LinkAddress linkAddress = newLinkAddress(addressInfo);
        Log.d(TAG, (isAdded ? "Adding" : "Removing") + " address " + linkAddress);

        updateTunInterfaceAddress(linkAddress, isAdded);
        updateNetworkLinkProperties(linkAddress, isAdded);
    }

    private boolean isMulticastForwardingEnabled() {
        return !(mUpstreamBuilder.build().getForwardingMode() == FORWARD_NONE
                && mDownstreamBuilder.build().getForwardingMode() == FORWARD_NONE);
    }

    private void sendUpdatedLocalNetworkConfig() {
        if (mNetworkAgent != null) {
            final LocalNetworkConfig.Builder configBuilder = new LocalNetworkConfig.Builder();
            LocalNetworkConfig localNetworkConfig =
                    configBuilder
                            .setUpstreamMulticastRoutingConfig(mUpstreamBuilder.build())
                            .setDownstreamMulticastRoutingConfig(mDownstreamBuilder.build())
                            .setUpstreamSelector(mUpstreamNetworkRequest)
                            .build();
            mNetworkAgent.sendLocalNetworkConfig(localNetworkConfig);
            Log.d(
                    TAG,
                    "Sent localNetworkConfig with upstreamConfig "
                            + mUpstreamBuilder.build().toString()
                            + " downstreamConfig"
                            + mDownstreamBuilder.build().toString());
        }
    }

    private void handleMulticastForwardingStateChanged(boolean isEnabled) {

        if (isMulticastForwardingEnabled() != isEnabled) {

            if (isEnabled) {
                // When multicast forwarding is enabled, setup upstream forwarding to any address
                // with minimal scope 4
                // setup downstream forwarding with addresses subscribed from Thread network
                mUpstreamBuilder = new MulticastRoutingConfig.Builder(FORWARD_WITH_MIN_SCOPE, 4);
                mDownstreamBuilder = new MulticastRoutingConfig.Builder(FORWARD_SELECTED);
            } else {
                // When multicast forwarding is disabled, set both upstream and downstream
                // forwarding config to FORWARD_NONE.
                mUpstreamBuilder = new MulticastRoutingConfig.Builder(FORWARD_NONE);
                mDownstreamBuilder = new MulticastRoutingConfig.Builder(FORWARD_NONE);
            }

            sendUpdatedLocalNetworkConfig();
            Log.d(
                    TAG,
                    "Sent updated localNetworkConfig with multicast forwarding "
                            + (isEnabled ? "enabled" : "disabled"));
        }
    }

    private void handleMulticastForwardingAddressChanged(byte[] addressBytes, boolean isAdded) {

        Inet6Address address = bytesToInet6Address(addressBytes);
        MulticastRoutingConfig oldDownstreamConfig = mDownstreamBuilder.build();

        if (mDownstreamBuilder.build().getForwardingMode() == MulticastRoutingConfig.FORWARD_NONE) {
            Log.e(
                    TAG,
                    "Ignor multicast listening address updates when downstream multicast forwarding"
                            + " is disabled!");
            // Don't update the address set if downstream multicast forwarding is disabled.
            return;
        }

        if (isAdded) {
            mDownstreamBuilder.addListeningAddress(address);
        } else {
            mDownstreamBuilder.clearListeningAddress(address);
        }

        if (!mDownstreamBuilder.build().equals(oldDownstreamConfig)) {
            Log.d(
                    TAG,
                    "Multicast listening address "
                            + address.getHostAddress()
                            + " is "
                            + (isAdded ? "added" : "removed"));
            sendUpdatedLocalNetworkConfig();
        }
    }

    private final class OtDaemonCallbackProxy extends IOtDaemonCallback.Stub {
        @GuardedBy("this")
        private final Map<IStateCallback, IBinder.DeathRecipient> mStateCallbacks = new HashMap<>();

        @GuardedBy("this")
        private final Map<IOperationalDatasetCallback, IBinder.DeathRecipient> mOpDatasetCallbacks =
                new HashMap<>();

        public synchronized void registerStateCallback(IStateCallback callback) {
            if (mStateCallbacks.containsKey(callback)) {
                return;
            }
            IBinder.DeathRecipient deathRecipient =
                    () -> mHandler.post(() -> unregisterStateCallback(callback));
            mStateCallbacks.put(callback, deathRecipient);
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                mStateCallbacks.remove(callback);
                // This is thrown when the client is dead, no necessary to propagate the exception
            }
        }

        public synchronized void unregisterStateCallback(IStateCallback callback) {
            if (!mStateCallbacks.containsKey(callback)) {
                return;
            }
            callback.asBinder().unlinkToDeath(mStateCallbacks.remove(callback), 0);
        }

        public synchronized void registerDatasetCallback(IOperationalDatasetCallback callback) {
            if (mOpDatasetCallbacks.containsKey(callback)) {
                return;
            }
            IBinder.DeathRecipient deathRecipient =
                    () -> mHandler.post(() -> unregisterDatasetCallback(callback));
            mOpDatasetCallbacks.put(callback, deathRecipient);
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                mOpDatasetCallbacks.remove(callback);
            }
        }

        public synchronized void unregisterDatasetCallback(IOperationalDatasetCallback callback) {
            if (!mOpDatasetCallbacks.containsKey(callback)) {
                return;
            }
            callback.asBinder().unlinkToDeath(mOpDatasetCallbacks.remove(callback), 0);
        }

        public void onOtDaemonBinderDeath() {
            // The Thread device role is considered DISABLED when the OT daemon process is dead
            mHandler.post(() -> handleDeviceRoleChanged(DEVICE_ROLE_DISABLED));
            synchronized (this) {
                for (IStateCallback callback : mStateCallbacks.keySet()) {
                    try {
                        callback.onDeviceRoleChanged(DEVICE_ROLE_DISABLED);
                    } catch (RemoteException ignored) {
                        // do nothing if the client is dead
                    }
                }
            }
        }

        @Override
        public void onInterfaceStateChanged(boolean isUp) {
            mHandler.post(() -> handleThreadInterfaceStateChanged(isUp));
        }

        @Override
        public void onDeviceRoleChanged(@DeviceRole int deviceRole) {
            mHandler.post(
                    () -> {
                        handleDeviceRoleChanged(deviceRole);
                        synchronized (this) {
                            for (IStateCallback callback : mStateCallbacks.keySet()) {
                                try {
                                    callback.onDeviceRoleChanged(deviceRole);
                                } catch (RemoteException ignored) {
                                    // do nothing if the client is dead
                                }
                            }
                        }
                    });
        }

        @Override
        public void onPartitionIdChanged(long partitionId) {
            mHandler.post(
                    () -> {
                        handlePartitionIdChanged(partitionId);
                        synchronized (this) {
                            for (IStateCallback callback : mStateCallbacks.keySet()) {
                                try {
                                    callback.onPartitionIdChanged(partitionId);
                                } catch (RemoteException ignored) {
                                    // do nothing if the client is dead
                                }
                            }
                        }
                    });
        }

        @Override
        public void onActiveOperationalDatasetChanged(byte[] activeOpDatasetTlvs) {
            ActiveOperationalDataset activeOpDataset;
            try {
                if (activeOpDatasetTlvs.length != 0) {
                    activeOpDataset = ActiveOperationalDataset.fromThreadTlvs(activeOpDatasetTlvs);
                } else {
                    activeOpDataset = null;
                }
            } catch (IllegalArgumentException e) {
                // Is unlikely that OT will generate invalid Operational Dataset
                Log.w(TAG, "Ignoring invalid Active Operational Dataset changes", e);
                return;
            }

            mHandler.post(
                    () -> {
                        handleActiveOperationalDatasetChanged(activeOpDataset);
                        synchronized (this) {
                            for (IOperationalDatasetCallback callback :
                                    mOpDatasetCallbacks.keySet()) {
                                try {
                                    callback.onActiveOperationalDatasetChanged(activeOpDataset);
                                } catch (RemoteException ignored) {
                                    // do nothing if the client is dead
                                }
                            }
                        }
                    });
        }

        @Override
        public void onPendingOperationalDatasetChanged(byte[] pendingOpDatasetTlvs) {
            PendingOperationalDataset pendingOpDataset;
            try {
                if (pendingOpDatasetTlvs.length != 0) {
                    pendingOpDataset =
                            PendingOperationalDataset.fromThreadTlvs(pendingOpDatasetTlvs);
                } else {
                    pendingOpDataset = null;
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Ignoring invalid Pending Operational Dataset changes", e);
                return;
            }

            mHandler.post(
                    () -> {
                        handlePendingOperationalDatasetChanged(pendingOpDataset);
                        synchronized (this) {
                            for (IOperationalDatasetCallback callback :
                                    mOpDatasetCallbacks.keySet()) {
                                try {
                                    callback.onPendingOperationalDatasetChanged(pendingOpDataset);
                                } catch (RemoteException ignored) {
                                    // do nothing if the client is dead
                                }
                            }
                        }
                    });
        }

        @Override
        public void onAddressChanged(Ipv6AddressInfo addressInfo, boolean isAdded)
                throws RemoteException {
            mHandler.post(() -> handleAddressChanged(addressInfo, isAdded));
        }

        @Override
        public void onMulticastForwardingStateChanged(boolean isEnabled) {
            mHandler.post(() -> handleMulticastForwardingStateChanged(isEnabled));
        }

        @Override
        public void onMulticastForwardingAddressChanged(byte[] address, boolean isAdded) {
            mHandler.post(() -> handleMulticastForwardingAddressChanged(address, isAdded));
        }
    }
}
