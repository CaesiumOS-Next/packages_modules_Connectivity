/*
 * Copyright 2023 The Android Open Source Project
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

package android.remoteauth;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.remoteauth.DeviceDiscoveryCallback.State.STATE_LOST;
import static android.remoteauth.DeviceDiscoveryCallback.State.STATE_SEEN;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * This class provides a way to perform RemoteAuth related operations such as
 * discovering, registering and authenticating via remote authenticator.
 *
 * <p> To get a {@link RemoteAuthManager} instance, call the
 * <code>Context.getSystemService(RemoteAuthManager.class)</code>.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
@SystemService(Context.REMOTE_AUTH_SERVICE)
public class RemoteAuthManager {
    private static final String TAG = "RemoteAuthManager";

    private final Context mContext;
    private final IRemoteAuthService mService;

    /** Maps between user callback and listener that is passed to the service
     * Used to find the right listener based on the user callback to stop the discovery
     */
    @GuardedBy("sDiscoveryListeners")
    private static final WeakHashMap<DeviceDiscoveryCallback,
            WeakReference<DeviceDiscoveryListenerTransport>>
            sDiscoveryListeners = new WeakHashMap<>();

    RemoteAuthManager(@NonNull Context context, @NonNull IRemoteAuthService service) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(service);
        mContext = context;
        mService = service;
    }

    /**********************************************************************/
    /** The following APIs are for Settings and Registration integration **/
    /**********************************************************************/


    /** This function is used by Settings to verify this device can be enrolled in Remote Unlock
     *
     * @return true if this device can be enrolled
     *
     * @hide
     */
    public boolean isRemoteAuthSupported() {
        try {
            return mService.isRemoteAuthSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Returns a list of registered authenticators.
     *
     * Going to be used by Settings to show a list to user to allow unregistration flow
     */
    @NonNull
    public List<RemoteAuthenticator> getRegisterAuthenticators() {
        try {
            return mService.getRegisterAuthenticators();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Starts remote authenticator discovery process with timeout
     *
     * @param callback to be used by the caller to get notifications about discovered devices
     * @param timeout in seconds to stop getting discovery updates (The value must be positive)
     * @param user user to discover devices for.
     *        Listeners are kept and notified in userId context
     * @param executor the callback will be executed in the executor thread
     * @return true if discovery began successfully
     *
     * @hide
     */
    public boolean startDiscovery(@NonNull DeviceDiscoveryCallback callback,
            int timeout,
            @NonNull UserHandle user,
            @CallbackExecutor @NonNull Executor executor) {
        try {
            Preconditions.checkArgument(callback != null,
                    "invalid null callback");
            Preconditions.checkArgument(user != null,
                    "invalid user");
            Preconditions.checkArgument(timeout > 0,
                    "invalid timeout, must be > 0");
            Preconditions.checkArgument(executor != null,
                    "invalid null executor");
            synchronized (sDiscoveryListeners) {
                WeakReference<DeviceDiscoveryListenerTransport> reference =
                        sDiscoveryListeners.get(callback);
                DeviceDiscoveryListenerTransport transport =
                        (reference != null) ? reference.get() : null;
                if (transport == null) {
                    transport = new DeviceDiscoveryListenerTransport(callback,
                            user.getIdentifier(),
                            executor);
                } else {
                    Preconditions.checkState(transport.isRegistered());
                    transport.setExecutor(executor);
                }
                boolean result = mService.registerDiscoveryListener(transport,
                        user.getIdentifier(),
                        timeout,
                        mContext.getPackageName(), mContext.getAttributionTag());
                if (result) {
                    sDiscoveryListeners.put(callback, new WeakReference<>(transport));
                    return true;
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return false;
    }

    /** Removes this listener from device discovery notifications
     *
     * @param callback that was used in startDiscovery
     *
     * @hide
     */
    public void stopDiscovery(@NonNull DeviceDiscoveryCallback callback) {
        Preconditions.checkArgument(callback != null,
                "invalid null scanCallback");
        try {
            synchronized (sDiscoveryListeners) {
                WeakReference<DeviceDiscoveryListenerTransport> reference =
                        sDiscoveryListeners.remove(callback);
                DeviceDiscoveryListenerTransport transport =
                        (reference != null) ? reference.get() : null;
                if (transport != null) {
                    transport.unregister();
                    mService.unregisterDiscoveryListener(transport,
                            transport.getUserId(),
                            mContext.getPackageName(),
                            mContext.getAttributionTag());
                } else {
                    Log.e(TAG, "Cannot stop discovery with this callback "
                            + "because it is never registered.");
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class DeviceDiscoveryListenerTransport extends IDeviceDiscoveryListener.Stub {

        private volatile @NonNull DeviceDiscoveryCallback mDeviceDiscoveryCallback;
        private Executor mExecutor;
        private int mUserId;

        DeviceDiscoveryListenerTransport(DeviceDiscoveryCallback deviceDiscoveryCallback,
                int userId,
                @CallbackExecutor Executor executor) {
            Preconditions.checkArgument(deviceDiscoveryCallback != null,
                    "invalid null callback");
            mDeviceDiscoveryCallback = deviceDiscoveryCallback;
            mUserId = userId;
            mExecutor = executor;
        }

        void setExecutor(Executor executor) {
            Preconditions.checkArgument(
                    executor != null, "invalid null executor");
            mExecutor = executor;
        }

        boolean isRegistered() {
            return mDeviceDiscoveryCallback != null;
        }

        void unregister() {
            mDeviceDiscoveryCallback = null;
        }

        int getUserId() {
            return mUserId;
        }

        @Override
        public void onDiscovered(DiscoveredDevice discoveredDevice) throws RemoteException {
            if (discoveredDevice != null) {
                Log.i(TAG, "Notifying the caller about discovered: " + discoveredDevice);
            }
            mExecutor.execute(() -> {
                if (mDeviceDiscoveryCallback != null && discoveredDevice != null) {
                    mDeviceDiscoveryCallback.onDeviceUpdate(discoveredDevice, STATE_SEEN);
                }
            });
        }

        @Override
        public void onLost(DiscoveredDevice discoveredDevice) throws RemoteException {
            if (discoveredDevice != null) {
                Log.i(TAG, "Notifying the caller about lost: " + discoveredDevice);
            }
            mExecutor.execute(() -> {
                if (mDeviceDiscoveryCallback != null && discoveredDevice != null) {
                    mDeviceDiscoveryCallback.onDeviceUpdate(discoveredDevice, STATE_LOST);
                }
            });
        }

        @Override
        public void onTimeout() {
            mExecutor.execute(() -> {
                if (mDeviceDiscoveryCallback != null) {
                    mDeviceDiscoveryCallback.onTimeout();
                }
            });
            sDiscoveryListeners.remove(mDeviceDiscoveryCallback);
            mDeviceDiscoveryCallback = null;
            Log.i(TAG, "Notifying the caller about discovery timeout");
        }
    }

    /** Registers a discovered authenticator.
     *
     * @param device a discovered device
     * @param user user to discover devices for.
     * @param callback to be used by the caller to get notifications about discovered devices
     * @param executor the callback will be executed in the executor thread
     * @return true if registration began successfully
     *
     * @hide
     */
    public boolean registerAuthenticator(@NonNull DiscoveredDevice device,
            @NonNull UserHandle user,
            @NonNull RegistrationCallback callback,
            @CallbackExecutor @NonNull Executor executor) {
        try {
            Preconditions.checkArgument(device != null,
                    "invalid null device");
            Preconditions.checkArgument(user != null,
                    "invalid null user");
            Preconditions.checkArgument(callback != null,
                    "invalid null callback");
            Preconditions.checkArgument(executor != null,
                    "invalid null executor");

            RegisterAuthenticatorListenerTransport transport =
                    new RegisterAuthenticatorListenerTransport(callback, executor);

            return mService.registerAuthenticatorListener(transport,
                    device,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class RegisterAuthenticatorListenerTransport extends
            IRegisterAuthenticatorListener.Stub {

        private volatile @NonNull RegistrationCallback mCallback;
        private Executor mExecutor;

        RegisterAuthenticatorListenerTransport(RegistrationCallback callback,
                @CallbackExecutor Executor executor) {
            Preconditions.checkArgument(callback != null,
                    "invalid null callback");
            Preconditions.checkArgument(executor != null,
                    "invalid null executor");
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onRegistered(RemoteAuthenticator remoteAuthenticator)
                throws RemoteException {
            if (remoteAuthenticator != null) {
                Log.i(TAG, "Notifying the caller about discovered: " + remoteAuthenticator);
            }
            mExecutor.execute(() -> {
                if (mCallback != null && remoteAuthenticator != null) {
                    mCallback.onRegistered(remoteAuthenticator);
                }
            });
        }

        @Override
        public void onFailure(int errorCode, CharSequence errString) throws RemoteException {
            if (errString != null) {
                Log.i(TAG, "Notifying the caller about registration error: " + errString);
            }
            mExecutor.execute(() -> {
                if (mCallback != null && errString != null) {
                    mCallback.onFailure(errorCode, errString);
                }
            });
        }
    }

    /** Unregisters authenticator.
     *
     * @param remoteAuthenticator an authenticator
     * @param callback to be used by the caller to know unregistration result
     * @param executor the callback will be executed in the executor thread
     * @return true if registration began successfully
     *
     * @hide
     */
    public boolean unregisterAuthenticator(@NonNull RemoteAuthenticator remoteAuthenticator,
            @NonNull UnregistrationCallback callback,
            @CallbackExecutor @NonNull Executor executor) {
        try {
            Preconditions.checkArgument(remoteAuthenticator != null,
                    "invalid null remoteAuthenticator");
            Preconditions.checkArgument(callback != null,
                    "invalid null callback");
            Preconditions.checkArgument(executor != null,
                    "invalid null executor");

            UnregisterAuthenticatorListenerTransport transport =
                    new UnregisterAuthenticatorListenerTransport(callback, executor);

            return mService.unregisterAuthenticatorListener(transport,
                    remoteAuthenticator);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class UnregisterAuthenticatorListenerTransport
            extends IUnregisterAuthenticatorListener.Stub {
        volatile @NonNull UnregistrationCallback mCallback;
        Executor mExecutor;

        UnregisterAuthenticatorListenerTransport(@NonNull UnregistrationCallback callback,
                @CallbackExecutor Executor executor) {
            Preconditions.checkArgument(callback != null,
                    "invalid null callback");
            Preconditions.checkArgument(executor != null,
                    "invalid null executor");
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onSuccess() throws RemoteException {
            Log.i(TAG, "Notifying the caller about successful un-registration");
            mExecutor.execute(() -> {
                if (mCallback != null) {
                    mCallback.onSuccess();
                }
            });
        }

        @Override
        public void onFailure(int errorCode, CharSequence errString) throws RemoteException {
            if (errString != null) {
                Log.i(TAG, "Notifying the caller about unregistration error: " + errString);
            }
            mExecutor.execute(() -> {
                if (mCallback != null && errString != null) {
                    mCallback.onFailure(errorCode, errString);
                }
            });
        }
    }
}
