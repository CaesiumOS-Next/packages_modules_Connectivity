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

import static android.net.thread.ActiveOperationalDataset.CHANNEL_PAGE_24_GHZ;
import static android.net.thread.ActiveOperationalDataset.LENGTH_EXTENDED_PAN_ID;
import static android.net.thread.ActiveOperationalDataset.LENGTH_MESH_LOCAL_PREFIX_BITS;
import static android.net.thread.ActiveOperationalDataset.LENGTH_NETWORK_KEY;
import static android.net.thread.ActiveOperationalDataset.LENGTH_PSKC;
import static android.net.thread.ActiveOperationalDataset.MESH_LOCAL_PREFIX_FIRST_BYTE;
import static android.net.thread.ActiveOperationalDataset.SecurityPolicy.DEFAULT_ROTATION_TIME_HOURS;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_CHILD;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DETACHED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_ROUTER;
import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;
import static android.net.thread.ThreadNetworkException.ERROR_TIMEOUT;
import static android.net.thread.ThreadNetworkException.ErrorCode;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;

import static com.android.server.thread.openthread.IOtDaemon.OT_ERROR_ABORT;
import static com.android.server.thread.openthread.IOtDaemon.OT_ERROR_DETACHED;
import static com.android.server.thread.openthread.IOtDaemon.OT_ERROR_RESPONSE_TIMEOUT;
import static com.android.server.thread.openthread.IOtDaemon.OT_ERROR_REASSEMBLY_TIMEOUT;
import static com.android.server.thread.openthread.IOtDaemon.TUN_IF_NAME;

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
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ActiveOperationalDataset.SecurityPolicy;
import android.net.thread.IActiveOperationalDatasetReceiver;
import android.net.thread.IOperationReceiver;
import android.net.thread.IOperationalDatasetCallback;
import android.net.thread.IScheduleMigrationReceiver;
import android.net.thread.IStateCallback;
import android.net.thread.IThreadNetworkController;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.DeviceRole;
import android.net.thread.ThreadNetworkException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.thread.openthread.IOtDaemon;
import com.android.server.thread.openthread.IOtDaemonCallback;
import com.android.server.thread.openthread.IOtStatusReceiver;
import com.android.server.thread.openthread.Ipv6AddressInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.Random;

/**
 * Implementation of the {@link ThreadNetworkController} API.
 *
 * Threading model:
 * This class is not Thread-safe and should only be accessed from the ThreadNetworkService class.
 * Additional attention should be paid to handle the threading code correctly:
 * 1. All member fields other than `mHandler` and `mContext` MUST be accessed from `mHandlerThread`
 * 2. In the @Override methods, the actual work MUST be dispatched to the HandlerThread except for
 * arguments or permissions checking
 */
final class ThreadNetworkControllerService extends IThreadNetworkController.Stub {
    private static final String TAG = "ThreadNetworkService";

    // Per Thread spec, it takes around 18 seconds for a detached device to join a new network,
    // sets the upper bound to 20 seconds for safety.
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(20);

    // 5 seconds should be a reasonable/safe timeout value for delivering a MGMT_SET.req message to
    // the Leader device. The timeout value is doubled because the caller needs to wait for a reply.
    private static final Duration SCHEDULE_MIGRATION_TIMEOUT = Duration.ofSeconds(10);

    // "goodbye" messages are sent when a device is requested to leave/detach from a network and
    // OpenThread waits for 1 second for the messages to be propagated in the Thread network. Sets
    // the timeout to 2 seconds to have some buffer for IPC.
    private static final Duration LEAVE_TIMEOUT = Duration.ofMillis(2000);

    // Below member fields can be accessed from both the binder and handler threads

    private final Context mContext;
    private final Handler mHandler;

    // Below member fields can only be accessed from the handler thread (`mHandlerThread`). In
    // particular, the constructor does not run on the handler thread, so it must not touch any of
    // the non-final fields, nor must it mutate any of the non-final fields inside these objects.

    private final HandlerThread mHandlerThread;
    private final NetworkProvider mNetworkProvider;
    private final Supplier<IOtDaemon> mOtDaemonSupplier;
    private final ConnectivityManager mConnectivityManager;
    private final TunInterfaceController mTunIfController;
    private final LinkProperties mLinkProperties = new LinkProperties();
    private final OtDaemonCallbackProxy mOtDaemonCallbackProxy = new OtDaemonCallbackProxy();
    private final List<JoinOperation> mOngoingJoinOperations = new LinkedList<>();
    private final List<ScheduleMigrationOperation> mOngoingMigrationOperations = new LinkedList<>();
    private final List<OutcomeReceiver<Void, ThreadNetworkException>> mOngoingLeaveReceivers =
            new LinkedList<>();

    // TODO(b/308310823): read supported channel from Thread dameon
    private final int mSupportedChannelMask = 0x07FFF800; // from channel 11 to 26

    private IOtDaemon mOtDaemon;
    private NetworkAgent mNetworkAgent;
    private ActiveOperationalDataset mActiveOpDataset;
    private PendingOperationalDataset mPendingOpDataset;
    private int mDeviceRole;
    private long mPartitionId;

    @VisibleForTesting
    ThreadNetworkControllerService(
            Context context,
            HandlerThread handlerThread,
            NetworkProvider networkProvider,
            Supplier<IOtDaemon> otDaemonSupplier,
            ConnectivityManager connectivityManager,
            TunInterfaceController tunIfController) {
        mContext = context;
        mHandlerThread = handlerThread;
        mHandler = new Handler(handlerThread.getLooper());
        mNetworkProvider = networkProvider;
        mOtDaemonSupplier = otDaemonSupplier;
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
                ServiceManagerWrapper::waitForThreadDaemonService,
                context.getSystemService(ConnectivityManager.class),
                new TunInterfaceController(TUN_IF_NAME));
    }

    private static NetworkCapabilities newNetworkCapabilities() {
        return new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .build();
    }

    private static InetAddress addressInfoToInetAddress(Ipv6AddressInfo addressInfo) {
        try {
            return InetAddress.getByAddress(addressInfo.address);
        } catch (UnknownHostException e) {
            // This is impossible unless the Thread daemon is critically broken
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
            case DEVICE_ROLE_STOPPED:
                return "stopped";
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

    @ErrorCode
    private static int otErrorToAndroidThreadError(int otError) {
        // TODO: cast error code to the Android Thread error code.
        // See external/openthread/include/openthread/error.h for OT error definition
        switch (otError) {
            case OT_ERROR_DETACHED:
                return ERROR_FAILED_PRECONDITION;
            case OT_ERROR_ABORT:
                return ERROR_ABORTED;
            case OT_ERROR_RESPONSE_TIMEOUT:
            case OT_ERROR_REASSEMBLY_TIMEOUT:
                return ERROR_TIMEOUT;
            default:
                return ERROR_INTERNAL_ERROR;
        }
    }

    private void initializeOtDaemon() {
        try {
            getOtDaemon();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to initialize ot-daemon");
        }
    }

    private IOtDaemon getOtDaemon() throws RemoteException {
        if (mOtDaemon != null) {
            return mOtDaemon;
        }

        IOtDaemon otDaemon = mOtDaemonSupplier.get();
        if (otDaemon == null) {
            throw new RemoteException("Internal error: failed to start OT daemon");
        }
        otDaemon.initialize(mTunIfController.getTunFd(), mOtDaemonCallbackProxy);
        otDaemon.asBinder().linkToDeath(() -> mHandler.post(this::onOtDaemonDead), 0);
        mOtDaemon = otDaemon;
        return mOtDaemon;
    }

    private void onOtDaemonDead() {
        Log.w(TAG, "OT daemon became dead, clean up...");
        mOtDaemonCallbackProxy.onOtDaemonBinderDeath();

        // For any reason the OT daemon process crashed, terminates all ongoing requests

        for (var leaveReceiver : mOngoingLeaveReceivers) {
            leaveReceiver.onError(
                    new ThreadNetworkException(ERROR_INTERNAL_ERROR, "Thread stack error"));
        }
        mOngoingLeaveReceivers.clear();

        for (JoinOperation joinOperation : mOngoingJoinOperations) {
            joinOperation.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
        mOngoingJoinOperations.clear();

        for (ScheduleMigrationOperation migrationOperation : mOngoingMigrationOperations) {
            migrationOperation.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
        mOngoingMigrationOperations.clear();

        mOtDaemon = null;
    }

    public void initialize() {
        mHandler.post(
                () -> {
                    Log.d(TAG, "Initializing Thread system service...");
                    try {
                        mTunIfController.createTunInterface();
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "Failed to create Thread tunnel interface", e);
                    }
                    mLinkProperties.setInterfaceName(TUN_IF_NAME);
                    mLinkProperties.setMtu(TunInterfaceController.MTU);
                    mConnectivityManager.registerNetworkProvider(mNetworkProvider);

                    initializeOtDaemon();
                });
    }

    private void registerThreadNetwork() {
        if (mNetworkAgent != null) {
            return;
        }
        NetworkCapabilities netCaps = newNetworkCapabilities();
        NetworkScore score =
                new NetworkScore.Builder()
                        .setKeepConnectedReason(NetworkScore.KEEP_CONNECTED_DOWNSTREAM_NETWORK)
                        .build();
        mNetworkAgent =
                newNetworkAgent(
                        mContext,
                        mHandlerThread.getLooper(),
                        TAG,
                        netCaps,
                        mLinkProperties,
                        score,
                        new NetworkAgentConfig.Builder().build(),
                        mNetworkProvider);
        mNetworkAgent.register();
        mNetworkAgent.markConnected();
        Log.i(TAG, "Registered Thread network");
    }

    private void unregisterThreadNetwork() {
        if (mNetworkAgent == null) {
            // unregisterThreadNetwork can be called every time this device becomes detached or
            // disabled
            return;
        }

        Log.d(TAG, "Unregistering Thread network agent");

        mNetworkAgent.unregister();
        mNetworkAgent = null;
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

        // The Thread daemon can send link property updates before the networkAgent is
        // registered
        if (mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(mLinkProperties);
        }
    }

    @Override
    public int getThreadVersion() {
        return THREAD_VERSION_1_3;
    }

    @Override
    public void createRandomizedDataset(
            String networkName, IActiveOperationalDatasetReceiver receiver) {
        mHandler.post(() -> {
            ActiveOperationalDataset dataset =
                    createRandomizedDatasetInternal(
                            networkName,
                            mSupportedChannelMask,
                            Instant.now(),
                            new Random(),
                            new SecureRandom());
            try {
                receiver.onSuccess(dataset);
            } catch (RemoteException e) {
                // The client is dead, do nothing
            }
        });
    }

    private static ActiveOperationalDataset createRandomizedDatasetInternal(
            String networkName,
            int supportedChannelMask,
            Instant now,
            Random random,
            SecureRandom secureRandom) {
        int panId = random.nextInt(/* bound= */ 0xffff);
        final byte[] meshLocalPrefix = newRandomBytes(random, LENGTH_MESH_LOCAL_PREFIX_BITS / 8);
        meshLocalPrefix[0] = MESH_LOCAL_PREFIX_FIRST_BYTE;

        final SparseArray<byte[]> channelMask = new SparseArray<>(1);
        channelMask.put(CHANNEL_PAGE_24_GHZ, intToByteArray(supportedChannelMask));

        final byte[] securityFlags = new byte[] {(byte) 0xff, (byte) 0xf8};

        return new ActiveOperationalDataset.Builder()
                .setActiveTimestamp(
                        new OperationalDatasetTimestamp(
                                now.getEpochSecond() & 0xffffffffffffL, 0, false))
                .setExtendedPanId(newRandomBytes(random, LENGTH_EXTENDED_PAN_ID))
                .setPanId(panId)
                .setNetworkName(networkName)
                .setChannel(CHANNEL_PAGE_24_GHZ, selectRandomChannel(supportedChannelMask, random))
                .setChannelMask(channelMask)
                .setPskc(newRandomBytes(secureRandom, LENGTH_PSKC))
                .setNetworkKey(newRandomBytes(secureRandom, LENGTH_NETWORK_KEY))
                .setMeshLocalPrefix(meshLocalPrefix)
                .setSecurityPolicy(new SecurityPolicy(DEFAULT_ROTATION_TIME_HOURS, securityFlags))
                .build();
    }

    private static byte[] newRandomBytes(Random random, int length) {
        byte[] result = new byte[length];
        random.nextBytes(result);
        return result;
    }

    private static byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value
        };
    }

    private static int selectRandomChannel(int supportedChannelMask, Random random) {
        int index = random.nextInt(Integer.bitCount(supportedChannelMask));
        for (int i = 0; i < 32; i++) {
            if ((supportedChannelMask & 1) == 1 && (index--) == 0) {
                return i;
            }
            supportedChannelMask = supportedChannelMask >> 1;
        }
        return -1;
    }

    private void enforceAllCallingPermissionsGranted(String... permissions) {
        for (String permission : permissions) {
            mContext.enforceCallingPermission(
                    permission, "Permission " + permission + " is missing");
        }
    }

    @Override
    public void registerStateCallback(IStateCallback stateCallback) throws RemoteException {
        enforceAllCallingPermissionsGranted(permission.ACCESS_NETWORK_STATE);
        mOtDaemonCallbackProxy.registerStateCallback(stateCallback);
    }

    @Override
    public void unregisterStateCallback(IStateCallback stateCallback) throws RemoteException {
        mOtDaemonCallbackProxy.unregisterStateCallback(stateCallback);
    }

    @Override
    public void registerOperationalDatasetCallback(IOperationalDatasetCallback callback)
            throws RemoteException {
        enforceAllCallingPermissionsGranted(
                permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
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

    private boolean isLeaving() {
        return !mOngoingLeaveReceivers.isEmpty();
    }

    private void checkOnHandlerThread() {
        if (Looper.myLooper() != mHandlerThread.getLooper()) {
            Log.wtf(TAG, "Must be on the handler thread!");
        }
    }

    @Override
    public void join(
            boolean doForm,
            @NonNull ActiveOperationalDataset activeOpDataset,
            @NonNull IActiveOperationalDatasetReceiver receiver) {
        enforceAllCallingPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        JoinOperation operation = new JoinOperation(doForm, activeOpDataset, receiver);
        mHandler.post(() -> joinInternal(operation));
    }

    private void joinInternal(JoinOperation operation) {
        checkOnHandlerThread();
        mOngoingJoinOperations.add(operation);

        // Wait for the leave operation to complete before start attaching
        if (isAttached() || isLeaving()) {
            leaveInternal(
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void ignored) {
                            joinFromLeft(operation);
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            if (mOngoingJoinOperations.remove(operation)) {
                                operation.onError(e.getErrorCode(), e.getMessage());
                            }
                        }
                    });
        } else {
            joinFromLeft(operation);
        }
    }

    private void joinFromLeft(JoinOperation operation) {
        try {
            getOtDaemon().join(
                    operation.getDoForm(),
                    operation.getActiveOperationalDataset().toThreadTlvs(),
                    new IOtStatusReceiver.Stub() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "otDaemon.join operation succeed");
                            // Continue waiting for the role becoming attached
                        }

                        @Override
                        public void onError(int otError, String message) {
                            mHandler.post(
                                    () -> {
                                        if (mOngoingJoinOperations.remove(operation)) {
                                            operation.onError(
                                                otErrorToAndroidThreadError(otError), message);
                                        }
                                    });
                        }
                    });
            mHandler.postDelayed(
                    () -> {
                        if (mOngoingJoinOperations.remove(operation)) {
                            operation.onError(
                                    ERROR_TIMEOUT,
                                    "It took more than %ds to join",
                                    JOIN_TIMEOUT.toSeconds());
                        }
                    },
                    JOIN_TIMEOUT.toMillis());
        } catch (RemoteException e) {
            Log.e(TAG, "otDaemon.join failed", e);
            mOngoingJoinOperations.remove(operation);
            operation.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
    }

    @Override
    public void scheduleMigration(
            PendingOperationalDataset pendingOpDataset,
            @NonNull IScheduleMigrationReceiver receiver)
            throws RemoteException {
        enforceAllCallingPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        mHandler.post(
                () ->
                        scheduleMigrationInternal(
                                new ScheduleMigrationOperation(pendingOpDataset, receiver)));
    }

    public void scheduleMigrationInternal(ScheduleMigrationOperation operation) {
        checkOnHandlerThread();
        if (!isAttached()) {
            operation.onError(
                    ERROR_FAILED_PRECONDITION, "Cannot schedule network migration when detached");
        }

        mOngoingMigrationOperations.add(operation);

        try {
            getOtDaemon().scheduleMigration(
                    operation.getPendingOperationalDataset().toThreadTlvs(),
                    new IOtStatusReceiver.Stub() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "Scheduled network migration on OT daemon");
                        }

                        @Override
                        public void onError(int otError, String message) {
                            mHandler.post(
                                    () -> {
                                        if (mOngoingMigrationOperations.remove(operation)) {
                                            operation.onError(
                                                    otErrorToAndroidThreadError(otError), message);
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
                                    "It took more than %ds to schedule network migration",
                                    SCHEDULE_MIGRATION_TIMEOUT.toSeconds());
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
    public void leave(@NonNull IOperationReceiver receiver) throws RemoteException {
        enforceAllCallingPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        mHandler.post(
                () -> {
                    for (JoinOperation joinOperation : mOngoingJoinOperations) {
                        joinOperation.onError(ERROR_ABORTED, "Leave() is requested");
                    }
                    mOngoingJoinOperations.clear();

                    for (ScheduleMigrationOperation migrationOperation :
                            mOngoingMigrationOperations) {
                        migrationOperation.onError(ERROR_ABORTED, "Leave() is requested");
                    }
                    mOngoingMigrationOperations.clear();

                    leaveInternal(
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

    private void leaveInternal(OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        checkOnHandlerThread();
        if (mDeviceRole == DEVICE_ROLE_STOPPED) {
            receiver.onResult(null);
            return;
        }

        mOngoingLeaveReceivers.add(receiver);

        try {
            getOtDaemon().leave(
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
                                        if (mOngoingLeaveReceivers.remove(receiver)) {
                                            receiver.onError(
                                                    new ThreadNetworkException(error, message));
                                        }
                                    });
                        }
                    });
            mHandler.postDelayed(
                    () -> {
                        if (mOngoingLeaveReceivers.remove(receiver)) {
                            // This cannot happen unless Thread daemon is broken
                            receiver.onError(
                                    new ThreadNetworkException(
                                            ERROR_INTERNAL_ERROR, "Thread stack error"));
                        }
                    },
                    LEAVE_TIMEOUT.toMillis());
        } catch (RemoteException e) {
            // Oneway AIDL API should never throw?
            mOngoingLeaveReceivers.remove(receiver);
            receiver.onError(
                    new ThreadNetworkException(ERROR_INTERNAL_ERROR, "Thread stack error"));
        }
    }

    private NetworkAgent newNetworkAgent(
            @NonNull Context context,
            @NonNull Looper looper,
            @NonNull String logTag,
            @NonNull NetworkCapabilities nc,
            @NonNull LinkProperties lp,
            @NonNull NetworkScore score,
            @NonNull NetworkAgentConfig config,
            @Nullable NetworkProvider provider) {
        return new NetworkAgent(context, looper, logTag, nc, lp, score, config, provider) {
            @Override
            public void onNetworkUnwanted() {
                // This can never happen because this agent is registered with
                // KEEP_CONNECTED_DOWNSTREAM_NETWORK, so CS will never call unwanted() on it.
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
        if (partitionId == mPartitionId) {
            return;
        }

        Log.d(TAG, String.format("Partition ID changed: %d -> %d", mPartitionId, partitionId));
        mPartitionId = partitionId;
    }

    private void handleDeviceRoleChanged(@DeviceRole int deviceRole) {
        if (deviceRole == mDeviceRole) {
            return;
        }

        Log.d(TAG, "State changed: " + deviceRoleToString(mDeviceRole) + " -> " + deviceRoleToString(deviceRole));
        mDeviceRole = deviceRole;

        if (isAttached()) {
            Log.d(TAG, "Attached to the Thread network");
            registerThreadNetwork();
        } else {
            Log.d(TAG, "Detached from the Thread network");
            unregisterThreadNetwork();
        }

        updateOngoingOperations();
    }

    private void handleActiveOperationalDatasetChanged(
            @Nullable ActiveOperationalDataset activeOpDataset) {
        if (Objects.equals(activeOpDataset, mActiveOpDataset)) {
            return;
        }

        Log.d(TAG, "Active Dataset updated: " + mActiveOpDataset + " -> " + activeOpDataset);
        mActiveOpDataset = activeOpDataset;

        updateOngoingOperations();
    }

    private void handlePendingOperationalDatasetChanged(
            @Nullable PendingOperationalDataset pendingOpDataset) {
        if (Objects.equals(pendingOpDataset, mPendingOpDataset)) {
            return;
        }

        Log.d(TAG, "Pending Dataset updated: " + mPendingOpDataset + " -> " + pendingOpDataset);
        mPendingOpDataset = pendingOpDataset;

        updateOngoingOperations();
    }

    /** Returns {@code true} if the two Active Datasets represent the same joinable network. */
    private static boolean isTheSameNetwork(
            ActiveOperationalDataset lhs, ActiveOperationalDataset rhs) {
        return Arrays.equals(lhs.getNetworkKey(), rhs.getNetworkKey())
                && lhs.getChannel() == rhs.getChannel();
    }

    /**
     * Updates ongoing API operations when Thread state or Dataset are changed.
     *
     * This method should be called immediately once state or Dataset has been updated.
     */
    private void updateOngoingOperations() {
        // Updates the join() operations

        final List<JoinOperation> finishedJoinOperations = new LinkedList<>();
        for (JoinOperation joinOperation : mOngoingJoinOperations) {
            if (!isAttached() || mActiveOpDataset == null) {
                continue;
            }

            if (isTheSameNetwork(mActiveOpDataset, joinOperation.getActiveOperationalDataset())) {
                joinOperation.onSuccess(mActiveOpDataset);
            } else {
                joinOperation.onError(
                        ERROR_ABORTED, "This device joined to a different network");
            }
            finishedJoinOperations.add(joinOperation);
        }
        mOngoingJoinOperations.removeAll(finishedJoinOperations);

        // Updates the scheduleMigration() operations

        List<ScheduleMigrationOperation> finishedMigrationOperations = new LinkedList<>();
        for (ScheduleMigrationOperation migrationOperation : mOngoingMigrationOperations) {
            if (mPendingOpDataset == null) {
                // A migration is done or hasn't been scheduled yet if there is no Pending Dataset

                if (!migrationOperation.isScheduled()) {
                    // Wait for the migrate being scheduled if it hasn't been scheduled yet
                    continue;
                }

                if (Objects.equals(
                        mActiveOpDataset, migrationOperation.getActiveOperationalDataset())) {
                    migrationOperation.onMigrated();
                } else {
                    migrationOperation.onError(
                            ERROR_ABORTED, "Migrated to a different network " + mActiveOpDataset);
                }
                finishedMigrationOperations.add(migrationOperation);
            } else if (!migrationOperation.isScheduled()) {
                // Matches only the Active Operational Dataset because the OT stack may override
                // the Delay Timer to recommended min or default values
                if (!mPendingOpDataset
                        .getActiveOperationalDataset()
                        .equals(migrationOperation.getActiveOperationalDataset())) {
                    // Keep waiting for the migration, if the Pending Dataset is different from
                    // what this operation scheduled. Because this may be the Pending Dataset
                    // registered by by another device and we may override it on the Leader later.

                    // TODO: listen to the MGMT_PENDING_SET.rsp replied by the Leader to determine
                    // whether this migration is successfully scheduled. But this is not consistent
                    // with our current implementation which exams the global Active / Pending
                    // Dataset state. For example, when the ScheduleMigrationReceiver#onError is
                    // called with an error code, the client can still receive the update of the
                    // same Pending Dataset via OperationalDatasetCallback.
                    continue;
                }

                Duration applyMigrationTimeout =
                        mPendingOpDataset.getDelayTimer().plus(SCHEDULE_MIGRATION_TIMEOUT);
                migrationOperation.onScheduled(mPendingOpDataset.getDelayTimer());
                mHandler.postDelayed(
                        () -> {
                            if (mOngoingMigrationOperations.remove(migrationOperation)) {
                                migrationOperation.onError(
                                        ERROR_TIMEOUT,
                                        "It took more than %ds to apply network migration",
                                        applyMigrationTimeout.toSeconds());
                            }
                        },
                        applyMigrationTimeout.toMillis());
                finishedMigrationOperations.add(migrationOperation);
            }
        }

        // Updates leave() operations

        if (mDeviceRole == DEVICE_ROLE_STOPPED) {
            for (var leaveReceiver : mOngoingLeaveReceivers) {
                leaveReceiver.onResult(null);
            }
            mOngoingLeaveReceivers.clear();
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

    private final class OtDaemonCallbackProxy extends IOtDaemonCallback.Stub {
        @GuardedBy("this")
        private final Map<IStateCallback, IBinder.DeathRecipient> mStateCallbacks = new HashMap<>();

        @GuardedBy("this")
        private final Map<IOperationalDatasetCallback, IBinder.DeathRecipient> mOpDatasetCallbacks =
                new HashMap<>();

        public synchronized void registerStateCallback(IStateCallback callback) {
            if (!mStateCallbacks.containsKey(callback)) {
                IBinder.DeathRecipient deathRecipient =
                        () -> mHandler.post(() -> unregisterStateCallback(callback));
                mStateCallbacks.put(callback, deathRecipient);
                try {
                    callback.asBinder().linkToDeath(deathRecipient, 0);
                } catch (RemoteException e) {
                    mStateCallbacks.remove(callback);
                    // This is thrown when the client is dead, do nothing
                    return;
                }
            }

            mHandler.post(
                    () -> {
                        try {
                            callback.onDeviceRoleChanged(mDeviceRole);
                            callback.onPartitionIdChanged(mPartitionId);
                        } catch (RemoteException e) {
                            // The client is dead, do nothing
                        }
                    });
        }

        public synchronized void unregisterStateCallback(IStateCallback callback) {
            if (!mStateCallbacks.containsKey(callback)) {
                return;
            }
            callback.asBinder().unlinkToDeath(mStateCallbacks.remove(callback), 0);
        }

        public synchronized void registerDatasetCallback(IOperationalDatasetCallback callback) {
            if (!mOpDatasetCallbacks.containsKey(callback)) {
                IBinder.DeathRecipient deathRecipient =
                        () -> mHandler.post(() -> unregisterDatasetCallback(callback));
                mOpDatasetCallbacks.put(callback, deathRecipient);
                try {
                    callback.asBinder().linkToDeath(deathRecipient, 0);
                } catch (RemoteException e) {
                    mOpDatasetCallbacks.remove(callback);
                }
            }

            mHandler.post(
                    () -> {
                        try {
                            callback.onActiveOperationalDatasetChanged(mActiveOpDataset);
                            callback.onPendingOperationalDatasetChanged(mPendingOpDataset);
                        } catch (RemoteException e) {
                            // The client is dead, do nothing
                        }
                    });
        }

        public synchronized void unregisterDatasetCallback(IOperationalDatasetCallback callback) {
            if (!mOpDatasetCallbacks.containsKey(callback)) {
                return;
            }
            callback.asBinder().unlinkToDeath(mOpDatasetCallbacks.remove(callback), 0);
        }

        public void onOtDaemonBinderDeath() {
            // The Thread device role is considered DISABLED when the OT daemon process is dead
            mHandler.post(() -> handleDeviceRoleChanged(DEVICE_ROLE_STOPPED));
            synchronized (this) {
                for (IStateCallback callback : mStateCallbacks.keySet()) {
                    try {
                        callback.onDeviceRoleChanged(DEVICE_ROLE_STOPPED);
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
    }
}
