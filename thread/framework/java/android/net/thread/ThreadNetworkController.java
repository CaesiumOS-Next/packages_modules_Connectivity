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

import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;

import static java.util.Objects.requireNonNull;

import android.Manifest.permission;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
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
 * Provides the primary APIs for controlling all aspects of a Thread network.
 *
 * <p>For example, attach this device to an existing network, form/create a new network with given
 * Thread Operational Dataset, or migrate an existing network.
 *
 * @hide
*/
@FlaggedApi("com.android.net.thread.flags.thread_enabled")
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

    private final Object mStateCallbackMapLock = new Object();

    @GuardedBy("mStateCallbackMapLock")
    private final Map<StateCallback, StateCallbackProxy> mStateCallbackMap = new HashMap<>();

    private final Object mOpDatasetCallbackMapLock = new Object();

    @GuardedBy("mOpDatasetCallbackMapLock")
    private final Map<OperationalDatasetCallback, OperationalDatasetCallbackProxy>
            mOpDatasetCallbackMap = new HashMap<>();

    ThreadNetworkController(@NonNull IThreadNetworkController controllerService) {
        requireNonNull(controllerService, "controllerService cannot be null");
        mControllerService = controllerService;
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

    /** Returns {@code true} if {@code deviceRole} indicates an attached state. */
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

    private final class StateCallbackProxy extends IStateCallback.Stub {
        private final Executor mExecutor;
        private final StateCallback mCallback;

        StateCallbackProxy(@CallbackExecutor Executor executor, StateCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onDeviceRoleChanged(@DeviceRole int deviceRole) {
            mExecutor.execute(() -> mCallback.onDeviceRoleChanged(deviceRole));
        }

        @Override
        public void onPartitionIdChanged(long partitionId) {
            mExecutor.execute(() -> mCallback.onPartitionIdChanged(partitionId));
        }
    }

    /**
     * Registers a callback to be called when Thread network states are changed.
     *
     * <p>Upon return of this method, methods of {@code callback} will be invoked immediately with
     * existing states.
     *
     * @param executor the executor to execute the {@code callback}
     * @param callback the callback to receive Thread network state changes
     * @throws IllegalArgumentException if {@code callback} has already been registered
     */
    @RequiresPermission(permission.ACCESS_NETWORK_STATE)
    public void registerStateCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull StateCallback callback) {
        requireNonNull(executor, "executor cannot be null");
        requireNonNull(callback, "callback cannot be null");
        synchronized (mStateCallbackMapLock) {
            if (mStateCallbackMap.containsKey(callback)) {
                throw new IllegalArgumentException("callback has already been registered");
            }
            StateCallbackProxy callbackProxy = new StateCallbackProxy(executor, callback);
            mStateCallbackMap.put(callback, callbackProxy);

            try {
                mControllerService.registerStateCallback(callbackProxy);
            } catch (RemoteException e) {
                mStateCallbackMap.remove(callback);
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters the Thread state changed callback.
     *
     * @param callback the callback which has been registered with {@link #registerStateCallback}
     * @throws IllegalArgumentException if {@code callback} hasn't been registered
     */
    public void unregisterStateCallback(@NonNull StateCallback callback) {
        requireNonNull(callback, "callback cannot be null");
        synchronized (mStateCallbackMapLock) {
            StateCallbackProxy callbackProxy = mStateCallbackMap.remove(callback);
            if (callbackProxy == null) {
                throw new IllegalArgumentException("callback hasn't been registered");
            }
            try {
                mControllerService.unregisterStateCallback(callbackProxy);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callback to receive notifications when the Thread Operational Datasets are changed.
     *
     * <p>Applications which are interested in monitoring Thread network datasets should implement
     * this interface and register the callback with {@link #registerOperationalDatasetCallback}.
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

    private final class OperationalDatasetCallbackProxy extends IOperationalDatasetCallback.Stub {
        private final Executor mExecutor;
        private final OperationalDatasetCallback mCallback;

        OperationalDatasetCallbackProxy(
                @CallbackExecutor Executor executor, OperationalDatasetCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onActiveOperationalDatasetChanged(
                @Nullable ActiveOperationalDataset activeOpDataset) {
            mExecutor.execute(() -> mCallback.onActiveOperationalDatasetChanged(activeOpDataset));
        }

        @Override
        public void onPendingOperationalDatasetChanged(
                @Nullable PendingOperationalDataset pendingOpDataset) {
            mExecutor.execute(() -> mCallback.onPendingOperationalDatasetChanged(pendingOpDataset));
        }
    }

    /**
     * Registers a callback to be called when Thread Operational Datasets are changed.
     *
     * <p>Upon return of this method, methods of {@code callback} will be invoked immediately with
     * existing Operational Datasets.
     *
     * @param executor the executor to execute {@code callback}
     * @param callback the callback to receive Operational Dataset changes
     * @throws IllegalArgumentException if {@code callback} has already been registered
     */
    @RequiresPermission(
            allOf = {permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED})
    public void registerOperationalDatasetCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OperationalDatasetCallback callback) {
        requireNonNull(executor, "executor cannot be null");
        requireNonNull(callback, "callback cannot be null");
        synchronized (mOpDatasetCallbackMapLock) {
            if (mStateCallbackMap.containsKey(callback)) {
                throw new IllegalArgumentException("callback has already been registered");
            }
            OperationalDatasetCallbackProxy callbackProxy =
                    new OperationalDatasetCallbackProxy(executor, callback);
            mOpDatasetCallbackMap.put(callback, callbackProxy);

            try {
                mControllerService.registerOperationalDatasetCallback(callbackProxy);
            } catch (RemoteException e) {
                mOpDatasetCallbackMap.remove(callback);
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters the Thread Operational Dataset callback.
     *
     * @param callback the callback which has been registered with {@link
     *     #registerOperationalDatasetCallback}
     * @throws IllegalArgumentException if {@code callback} hasn't been registered
     */
    public void unregisterOperationalDatasetCallback(@NonNull OperationalDatasetCallback callback) {
        requireNonNull(callback, "callback cannot be null");
        synchronized (mOpDatasetCallbackMapLock) {
            OperationalDatasetCallbackProxy callbackProxy = mOpDatasetCallbackMap.remove(callback);
            if (callbackProxy == null) {
                throw new IllegalArgumentException("callback hasn't been registered");
            }
            try {
                mControllerService.unregisterOperationalDatasetCallback(callbackProxy);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Attaches to or forms a network if it hasn't been formed by a Router.
     *
     * <p>This method does nothing if this device has already attached to the same network specified
     * by {@code activeOpDataset}. If this device already attached to a different network, this
     * device will first detach from that network and then form the new network. This method changes
     * only this device and all other devices will stay in the old network. To change the network
     * for all connected devices together, use {@link #scheduleMigration}.
     *
     * <p>Note that only one request of this operation will be executed at the same time. An ongoing
     * {@link attachOrForm} operation will be aborted by a subsequent {@link #attachOrForm} or
     * {@link detach} operation which may be initiated by the caller itself or other parties.
     *
     * <p>On success, this device is attached as a Child, Router or Leader. Otherwise, {@link
     * OutcomeReceiver#onError} of {@code receiver} will be invoked with a specific error:
     *
     * <ul>
     *   <li>{@link ThreadNetworkException#ERROR_ABORTED} the operation is aborted by a subsequent
     *       {@link #attachOrForm} or {@link #detach} operation
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
    @RequiresPermission(PERMISSION_THREAD_NETWORK_PRIVILEGED)
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
         * @param delayTimer the delay after which the Pending Operational Dataset will be applied
         *     for all Thread devices. This is the value accepted by the Leader device and it
         *     doesn't necessary match {@link PendingOperationalDataset#getDelayTimer()} which is
         *     specified via {@link ThreadNetworkController#scheduleMigration}. Instead, it's chosen
         *     by the leader based on current network state (e.g. size and topology)
         */
        void onScheduled(@NonNull Duration delayTimer);

        /**
         * Called when the migration is successfully applied in the network. This doesn't guarantee
         * that all devices in the current network have been updated to the new network, given a
         * device may fail to receive the Pending Operational Dataset.
         *
         * <p>This will be called after {@link #onScheduled} by the returned delay timer if {@link
         * #onError} hasn't been called.
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
         *       Leader device
         *   <li>{@link ThreadNetworkException#ERROR_TIMEOUT} the operation doesn't complete before
         *       the deadline.
         *   <li>{@link ThreadNetworkException#ERROR_FAILED_PRECONDITION} the operation is requested
         *       when this device is not attached to network
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
     * <p>This method should be called only when this device is attached, otherwise, a {@link
     * ThreadNetworkException#ERROR_FAILED_PRECONDITION} will be returned via the {@code receiver}.
     * To apply the Thread network changes to all devices in the network, the migration doesn't
     * happen immediately but is scheduled after {@code pendingOpDataset.getDelayTimer()} on all
     * devices. On success, {@link ScheduleMigrationReceiver#onScheduled} will be called when the
     * migration is successfully scheduled on the Leader (i.e. {@code pendingOpDataset} has been
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
     * It's important to select a proper value to safely migrate all devices in the network without
     * leaving sleepy end devices orphaned. Apps are not suggested to specify the Delay Timer value
     * if it's unclear how long it can take to propagate the {@code pendingOpDataset} to the whole
     * network. Instead, use {@link Duration#ZERO} to use the default value suggested by the system.
     *
     * <p>Note that the {@code pendingOpDataset} will be persisted on the Leader device between
     * when {@link ScheduleMigrationReceiver#onScheduled} and {@link
     * ScheduleMigrationReceiver#onMigrated} are called. If this device happens to be the Leader
     * device and is rebooted before {@link ScheduleMigrationReceiver#onMigrated} is called, the
     * migration will still be applied if this device attaches to or forms the same Thread network
     * after power cycle.
     *
     * @param pendingOpDataset the Pending Operational Dataset
     * @param executor the executor to execute {@code receiver}
     * @param receiver the receiver to receive result of this operation
     */
    @RequiresPermission(PERMISSION_THREAD_NETWORK_PRIVILEGED)
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
    @RequiresPermission(PERMISSION_THREAD_NETWORK_PRIVILEGED)
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
     * if this device is not attached.
     */
    @Nullable
    public Network getNetwork() {
        try {
            return mControllerService.getNetwork();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the randomly generated 64-bit extended MAC address.
     *
     * @hide
     */
    @NonNull
    @RequiresPermission(permission.LOCAL_MAC_ADDRESS)
    public byte[] getExtendedMacAddress() {
        try {
            return mControllerService.getExtendedMacAddress();
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
}
