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

package android.net.thread;

import static java.util.Objects.requireNonNull;

import android.Manifest.permission;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.net.Network;
import android.os.OutcomeReceiver;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Provides the primary API for controlling all aspects of a Thread network.
 *
 * @hide
 */
@SystemApi
public class ThreadNetworkController {
    private static final String TAG = "ThreadNetworkController";

    /** The Thread stack is disabled. */
    public static final int DEVICE_ROLE_DISABLED = 0;

    /** The device is not currently participating in a Thread network/partition. */
    public static final int DEVICE_ROLE_DETACHED = 1;

    /** The device is a Thread Child. */
    public static final int DEVICE_ROLE_CHILD = 2;

    /** The device is a Thread Router. */
    public static final int DEVICE_ROLE_ROUTER = 3;

    /** The device is a Thread Leader. */
    public static final int DEVICE_ROLE_LEADER = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        DEVICE_ROLE_DISABLED,
        DEVICE_ROLE_DETACHED,
        DEVICE_ROLE_CHILD,
        DEVICE_ROLE_ROUTER,
        DEVICE_ROLE_LEADER
    })
    public @interface DeviceRole {}

    /** Thread standard version 1.3. */
    public static final int THREAD_VERSION_1_3 = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({THREAD_VERSION_1_3})
    public @interface ThreadVersion {}

    private final IThreadNetworkController mControllerService;
    private final StateCallbackProxy mStateCallbackProxy;
    private final OperationalDatasetCallbackProxy mOpDatasetCallbackProxy;

    ThreadNetworkController(@NonNull IThreadNetworkController controllerService) {
        this(
                controllerService,
                new StateCallbackProxy(controllerService),
                new OperationalDatasetCallbackProxy(controllerService));
    }

    private ThreadNetworkController(
            @NonNull IThreadNetworkController controllerService,
            StateCallbackProxy callbackProxy,
            OperationalDatasetCallbackProxy opDatasetCallbackProxy) {
        requireNonNull(controllerService, "controllerService cannot be null");

        mControllerService = controllerService;
        this.mStateCallbackProxy = callbackProxy;
        this.mOpDatasetCallbackProxy = opDatasetCallbackProxy;
    }

    /** Returns the Thread version this device is operating on. */
    @ThreadVersion
    public int getThreadVersion() {
        try {
            return mControllerService.getThreadVersion();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if {@code deviceRole} indicates that we are attached.
     *
     * @hide
     */
    public static boolean isAttached(@DeviceRole int deviceRole) {
        return deviceRole == DEVICE_ROLE_CHILD
                || deviceRole == DEVICE_ROLE_ROUTER
                || deviceRole == DEVICE_ROLE_LEADER;
    }

    /**
     * Callback to receive notifications when the Thread network states are changed.
     *
     * <p>Applications which are interested in monitoring Thread network states should implement
     * this interface and register the callback with {@link #registerStateCallback}.
     */
    public interface StateCallback {
        /**
         * The Thread device role has changed.
         *
         * @param deviceRole the new Thread device role
         */
        void onDeviceRoleChanged(@DeviceRole int deviceRole);

        /**
         * The Thread network partition ID has changed.
         *
         * @param partitionId the new Thread partition ID
         */
        default void onPartitionIdChanged(long partitionId) {}
    }

    /**
     * Registers a callback to be called when Thread network states are changed.
     *
     * @param executor the executor to execute the {@code callback}
     * @param callback the callback to receive Thread network state changes
     * @throws SecurityException if the caller doesn't hold the {@link
     *     permission.ACCESS_NETWORK_STATE} permissions
     */
    @RequiresPermission(permission.ACCESS_NETWORK_STATE)
    public void registerStateCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull StateCallback callback) {
        requireNonNull(executor, "executor cannot be null");
        requireNonNull(callback, "callback cannot be null");
        mStateCallbackProxy.register(executor, callback);
    }

    /**
     * Unregisters the Thread state changed callback.
     *
     * @param callback the callback which has been registered with {@link #registerStateCallback}
     */
    public void unregisterStateCallback(@NonNull StateCallback callback) {
        requireNonNull(callback, "callback cannot be null");
        mStateCallbackProxy.unregister(callback);
    }

    /**
     * Callback to receive notifications when the Thread Operational Datasets are changed.
     *
     * <p>Applications which are interested in monitoring Thread network states should implement
     * this interface and register the callback with {@link #registerStateCallback}.
     */
    public interface OperationalDatasetCallback {
        /**
         * Called when the Active Operational Dataset is changed.
         *
         * @param activeOpDataset the new Active Operational Dataset or {@code null} if the dataset
         *     is absent
         */
        void onActiveOperationalDatasetChanged(@Nullable ActiveOperationalDataset activeOpDataset);

        /**
         * Called when the Pending Operational Dataset is changed.
         *
         * @param pendingOpDataset the new Pending Operational Dataset or {@code null} if the
         *     dataset has been committed and removed
         */
        default void onPendingOperationalDatasetChanged(
                @Nullable PendingOperationalDataset pendingOpDataset) {}
    }

    /**
     * Registers a callback to be called when Thread Operational Datasets are changed.
     *
     * @param executor the executor to execute {@code callback}
     * @param callback the callback to receive Operational Dataset changes
     */
    @RequiresPermission(
            allOf = {permission.ACCESS_NETWORK_STATE, permission.THREAD_NETWORK_PRIVILEGED})
    public void registerOperationalDatasetCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OperationalDatasetCallback callback) {
        requireNonNull(executor, "executor cannot be null");
        requireNonNull(callback, "callback cannot be null");
        mOpDatasetCallbackProxy.register(executor, callback);
    }

    /**
     * Unregisters the Thread Operational Dataset callback.
     *
     * @param callback the callback which has been registered with {@link
     *     #registerOperationalDatasetCallback}
     */
    public void unregisterOperationalDatasetCallback(@NonNull OperationalDatasetCallback callback) {
        requireNonNull(callback, "callback cannot be null");
        mOpDatasetCallbackProxy.unregister(callback);
    }

    /**
     * Attaches to or forms a network if it hasn't been formed by a Router.
     *
     * <p>This method does nothing if we are already attached to the same network specified by
     * {@code activeOpDataset}. If it's already attached to a different network, this device will
     * first detach from that network and then form the new network. This changes only this device
     * and all other devices may stay in the old network. To change the network for all connected
     * devices together, use {@link #scheduleMigration}.
     *
     * <p>Note that only one request of this operation will be executed at the same time. An ongoing
     * {@link attachOrForm} operation will be aborted by a subsequent {@link attachOrForm}, {@link
     * detach} or {@link reset} operation which may be initiated by the caller itself or other
     * parties.
     *
     * <p>On success, this device is attached as a Child, Router or Leader. Otherwise, {@link
     * OutcomeReceiver#onError} of {@code receiver} will be invoked with a specific error:
     *
     * <ul>
     *   <li>{@link ThreadNetworkException#ERROR_ABORTED} the operation is aborted by a subsequent
     *       {@link #attachOrForm}, {@link #attach} or {@link #detach} operation
     *   <li>{@link ThreadNetworkException#ERROR_TIMEOUT} the operation doesn't complete before the
     *       deadline.
     *   <li>{@link ThreadNetworkException#ERROR_INTERNAL_ERROR} the operation fails because of
     *       unknown errors
     * </ul>
     *
     * @param activeOpDataset the Active Operational Dataset which is used to attach to or form the
     *     new Thread network
     * @param executor the executor to execute {@code receiver}
     * @param receiver the receiver to receive result of this operation
     */
    @RequiresPermission(permission.THREAD_NETWORK_PRIVILEGED)
    public void attachOrForm(
            @NonNull ActiveOperationalDataset activeOpDataset,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        requireNonNull(activeOpDataset, "activeOpDataset cannot be null");
        requireNonNull(executor, "executor cannot be null");
        requireNonNull(receiver, "receiver cannot be null");
        try {
            mControllerService.attach(
                    /* doForm= */ true,
                    activeOpDataset,
                    new OperationCallbackProxy(executor, receiver));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Receives result of {@link #scheduleMigration}. */
    public interface ScheduleMigrationReceiver {
        /**
         * Called when the network migration is successfully scheduled on the Leader.
         *
         * @param delayTimer the Delay Timer accepted by the Leader device. This value doesn't
         *     necessary matches {@link PendingOperationalDataset#getDelayTimer()} which is
         *     specified via {@link ThreadNetworkController#scheduleMigration}. Instead, it's chosen
         *     by the leader based on current network state (e.g. size and topology)
         */
        void onScheduled(@NonNull Duration delayTimer);

        /**
         * Called when the migration is successfully applied in the network. This doesn't guarantee
         * that all devices in the current network have been updated to the new network, given a
         * device may fail to receive the Pending Operational Dataset.
         *
         * <p>This will be called after {@link #onScheduled} by the returned delay timer
         */
        void onMigrated();

        /**
         * Called whenever an error is encountered.
         *
         * <p>Specific error code will be returned in {@error}:
         *
         * <ul>
         *   <li>{@link ThreadNetworkException#ERROR_ABORTED} the operation is aborted by a
         *       subsequent {@link #scheduleMigration} or {@link #detach} operation
         *   <li>{@link ThreadNetworkException#ERROR_REJECTED} the operation is rejected by the
         *       leader
         *   <li>{@link ThreadNetworkException#ERROR_TIMEOUT} the operation doesn't complete before
         *       the deadline.
         *   <li>{@link ThreadNetworkException#ERROR_ILLEGAL_STATE} the operation is requested when
         *       this device is not attached to network
         *   <li>{@link ThreadNetworkException#ERROR_INTERNAL_ERROR} the operation fails because of
         *       unknown errors
         * </ul>
         */
        void onError(@NonNull ThreadNetworkException error);
    }

    /**
     * Schedules a network migration which moves all devices in the current connected network to a
     * new network or updates parameters of the current connected network.
     *
     * <p>This method should be called only when we are attached, otherwise, a {@link
     * ThreadNetworkException#ERROR_FAILED_PRECONDITION} will be returned via the {@code receiver}.
     * To applied the Thread network changes to all devices in the network, the migration doesn't
     * happen immediately but is scheduled after {@code pendingOpDataset.getDelayTimer()} on the
     * Leader device. On success, {@link ScheduleMigrationReceiver#onScheduled} will be called when
     * the migration is successfully scheduled on the Leader (i.e. {@code pendingOpDataset} has been
     * registered on the Leader); and {@link ScheduleMigrationReceiver#onMigrated} will be called
     * when {@code pendingOpDataset.getActiveOperationalDataset()} has been successfully applied to
     * all devices after {@code pendingOpDataset.getDelayTimer()}. It will also deliver asynchronous
     * Thread network state changed events to {@link
     * StateCallback#onPendingOperationalDatasetChanged} and {@link
     * StateCallback#onActiveOperationalDatasetChanged} if a callback has been registered with
     * {@link #registerStateCallback}. When failed, {@link ScheduleMigrationReceiver#onError} will
     * be called with a specific error.
     *
     * <p>The Delay Timer of {@code pendingOpDataset} can vary from several minutes to a few days.
     * It's important to select a proper value to safely migration all devices in the network
     * without leaving sleepy end devices orphaned. Apps are not suggested to specify the Delay
     * Timer value if it's unclear how long it can take to propagate the {@code pendingOpDataset} to
     * the whole network. Instead, use {@link Duration#ZERO} to use the default value suggested by
     * the system.
     *
     * <p>Note that the {@code pendingOpDataset} will be persisted on the Leader device between
     * {@link ScheduleMigrationReceiver#onScheduled} and {@link
     * ScheduleMigrationReceiver#onMigrated} are called. If this device happens to be the Leader
     * device and is rebooted before {@link ScheduleMigrationReceiver#onMigrated} is called, the
     * migration will still be applied if this device attaches to or forms the same Thread network
     * after power cycle.
     *
     * @param pendingOpDataset the Pending Operational Dataset
     * @param executor the executor to execute {@code receiver}
     * @param receiver the receiver to receive result of this operation
     */
    @RequiresPermission(permission.THREAD_NETWORK_PRIVILEGED)
    public void scheduleMigration(
            @NonNull PendingOperationalDataset pendingOpDataset,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ScheduleMigrationReceiver receiver) {
        requireNonNull(pendingOpDataset, "pendingOpDataset cannot be null");
        requireNonNull(executor, "executor cannot be null");
        requireNonNull(receiver, "receiver cannot be null");
        try {
            mControllerService.scheduleMigration(
                    pendingOpDataset,
                    new IScheduleMigrationReceiver.Stub() {
                        @Override
                        public void onScheduled(long delayTimerMillis) {
                            executor.execute(
                                    () -> {
                                        receiver.onScheduled(Duration.ofMillis(delayTimerMillis));
                                    });
                        }

                        @Override
                        public void onMigrated() {
                            executor.execute(receiver::onMigrated);
                        }

                        @Override
                        public void onError(int errorCode, String errorMessage) {
                            executor.execute(
                                    () ->
                                            receiver.onError(
                                                    new ThreadNetworkException(
                                                            errorCode, errorMessage)));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Detaches from the Thread network.
     *
     * @param executor the executor to execute {@code receiver}
     * @param receiver the receiver to receive result of this operation
     */
    @RequiresPermission(permission.THREAD_NETWORK_PRIVILEGED)
    public void detach(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        requireNonNull(executor, "executor cannot be null");
        requireNonNull(receiver, "receiver cannot be null");
        try {
            mControllerService.detach(new OperationCallbackProxy(executor, receiver));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link Network} object associated to the current Thread network, or {@code null}
     * if we are not attached.
     */
    @Nullable
    public Network getCurrentNetwork() {
        try {
            return mControllerService.getCurrentNetwork();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Returns the randomly generated 64-bit extended MAC address. */
    @NonNull
    @RequiresPermission(permission.ACCESS_NETWORK_STATE)
    public byte[] getExtendedMacAddress() {
        try {
            return mControllerService.getExtendedMacAddress();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Returns {@code true} if the Thread Border Router feature is supported. */
    public boolean isBorderRouterSupported() {
        try {
            return mControllerService.isBorderRouterSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Configures the Border Router feature.
     *
     * <p>This method should be called only when {@link isBorderRouterSupported} returns true.
     *
     * @param borderRouterConfig the new Border Router configuration
     * @param executor the executor to execute {@code receiver}
     * @param receiver the receiver to receive result of this operation
     * @throws UnsupportedOperationException if the Border Router feature is not supported
     */
    @RequiresPermission(permission.THREAD_NETWORK_PRIVILEGED)
    public void setBorderRouterConfiguration(
            @NonNull BorderRouterConfiguration borderRouterConfig,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        requireNonNull(borderRouterConfig, "borderRouterConfig cannot be null");
        requireNonNull(executor, "executor cannot be null");
        requireNonNull(receiver, "receiver cannot be null");
        try {
            mControllerService.setBorderRouterConfiguration(
                    borderRouterConfig, new OperationCallbackProxy(executor, receiver));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the Border Router configuration or {@code null} if the Border Router feature is not
     * supported.
     *
     * @throws SecurityException if the caller doesn't hold the {@link
     *     permission.ACCESS_NETWORK_STATE} permission
     */
    @Nullable
    @RequiresPermission(permission.ACCESS_NETWORK_STATE)
    public BorderRouterConfiguration getBorderRouterConfiguration() {
        try {
            return mControllerService.getBorderRouterConfiguration();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static final class OperationCallbackProxy extends IOperationReceiver.Stub {
        final Executor executor;
        final OutcomeReceiver<Void, ThreadNetworkException> resultReceiver;

        OperationCallbackProxy(
                @CallbackExecutor Executor executor,
                OutcomeReceiver<Void, ThreadNetworkException> resultReceiver) {
            this.executor = executor;
            this.resultReceiver = resultReceiver;
        }

        @Override
        public void onSuccess() {
            executor.execute(() -> resultReceiver.onResult(null));
        }

        @Override
        public void onError(int errorCode, String errorMessage) {
            executor.execute(
                    () ->
                            resultReceiver.onError(
                                    new ThreadNetworkException(errorCode, errorMessage)));
        }
    }

    private static final class StateCallbackProxy extends IStateCallback.Stub {
        private final IThreadNetworkController mControllerService;

        @GuardedBy("this")
        private final Map<StateCallback, Executor> mCallbacks = new HashMap<>();

        StateCallbackProxy(IThreadNetworkController service) {
            mControllerService = service;
        }

        public void register(@CallbackExecutor Executor executor, StateCallback callback) {
            synchronized (this) {
                if (mCallbacks.isEmpty()) {
                    try {
                        mControllerService.registerStateCallback(this);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
                mCallbacks.put(callback, executor);
            }
        }

        public void unregister(StateCallback callback) {
            synchronized (this) {
                mCallbacks.remove(callback);
                if (mCallbacks.isEmpty()) {
                    try {
                        mControllerService.unregisterStateCallback(this);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }

        @Override
        public void onDeviceRoleChanged(@DeviceRole int deviceRole) {
            synchronized (this) {
                for (Map.Entry<StateCallback, Executor> entry : mCallbacks.entrySet()) {
                    StateCallback callback = entry.getKey();
                    Executor executor = entry.getValue();
                    executor.execute(() -> callback.onDeviceRoleChanged(deviceRole));
                }
            }
        }

        @Override
        public void onPartitionIdChanged(long partitionId) {
            synchronized (this) {
                for (Map.Entry<StateCallback, Executor> entry : mCallbacks.entrySet()) {
                    StateCallback callback = entry.getKey();
                    Executor executor = entry.getValue();
                    executor.execute(() -> callback.onPartitionIdChanged(partitionId));
                }
            }
        }
    }

    private static final class OperationalDatasetCallbackProxy
            extends IOperationalDatasetCallback.Stub {
        private final IThreadNetworkController mControllerService;

        @GuardedBy("this")
        private final Map<OperationalDatasetCallback, Executor> mCallbacks = new HashMap<>();

        OperationalDatasetCallbackProxy(IThreadNetworkController service) {
            mControllerService = service;
        }

        public void register(
                @CallbackExecutor Executor executor, OperationalDatasetCallback callback) {
            synchronized (this) {
                if (mCallbacks.isEmpty()) {
                    try {
                        mControllerService.registerOperationalDatasetCallback(this);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
                mCallbacks.put(callback, executor);
            }
        }

        public void unregister(OperationalDatasetCallback callback) {
            synchronized (this) {
                mCallbacks.remove(callback);
                if (mCallbacks.isEmpty()) {
                    try {
                        mControllerService.unregisterOperationalDatasetCallback(this);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }

        @Override
        public void onActiveOperationalDatasetChanged(
                @Nullable ActiveOperationalDataset activeOpDataset) {
            synchronized (this) {
                for (Map.Entry<OperationalDatasetCallback, Executor> entry :
                        mCallbacks.entrySet()) {
                    OperationalDatasetCallback callback = entry.getKey();
                    Executor executor = entry.getValue();
                    executor.execute(
                            () -> callback.onActiveOperationalDatasetChanged(activeOpDataset));
                }
            }
        }

        @Override
        public void onPendingOperationalDatasetChanged(
                @Nullable PendingOperationalDataset pendingOpDataset) {
            synchronized (this) {
                for (Map.Entry<OperationalDatasetCallback, Executor> entry :
                        mCallbacks.entrySet()) {
                    OperationalDatasetCallback callback = entry.getKey();
                    Executor executor = entry.getValue();
                    executor.execute(
                            () -> callback.onPendingOperationalDatasetChanged(pendingOpDataset));
                }
            }
        }
    }
}
