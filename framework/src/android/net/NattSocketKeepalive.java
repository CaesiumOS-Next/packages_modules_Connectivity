/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.net.InetAddress;
import java.util.concurrent.Executor;

/** @hide */
public final class NattSocketKeepalive extends SocketKeepalive {
    /** The NAT-T destination port for IPsec */
    public static final int NATT_PORT = 4500;

    @NonNull private final InetAddress mSource;
    @NonNull private final InetAddress mDestination;
    private final int mResourceId;

    public NattSocketKeepalive(@NonNull IConnectivityManager service,
            @NonNull Network network,
            @NonNull ParcelFileDescriptor pfd,
            int resourceId,
            @NonNull InetAddress source,
            @NonNull InetAddress destination,
            @NonNull Executor executor,
            @NonNull Callback callback) {
        super(service, network, pfd, executor, callback);
        mSource = source;
        mDestination = destination;
        mResourceId = resourceId;
    }

    /**
     * Request that keepalive be started with the given {@code intervalSec}.
     * See {@link SocketKeepalive}.
     *
     * Set {@code automaticOnOffKeepalives} to {@code true} to allow the system to disable sending
     * keepalive packets when there are no TCP connections over the VPN to save battery, and
     * and restart sending them as soon as any TCP connection is opened over the VPN network.
     * This works only when {@link #mNetwork} is the underlying network of the VPN network.
     * That is, this will not affect other non-VPN NATT keepalive sockets.
     *
     * The socket fd will not be closed when keepalive is temporarily disabled because of
     * {@code automaticOnOffKeepalives} to maintain the original fd alive until the keepalive is
     * stopped by the owner, or no VPN network available in the device since the keepalive should
     * also be renewed.
     *
     * @param intervalSec The target interval in seconds between keepalive packet transmissions.
     *                    The interval should be between 10 seconds and 3600 seconds. Otherwise,
     *                    it will result in a callback with {@link #ERROR_INVALID_INTERVAL}.
     * @param automaticOnOffKeepalives A boolean indicating whether the keepalive will be
     *                                 automatically disabled when there are no TCP connections
     *                                 open over the VPN to save battery, and re-enabled if any TCP
     *                                 connection opened over the VPN network.
     *                                 {@code false} will always send the keepalives.
     * @hide
     */
    @Override
    protected void startImpl(int intervalSec, boolean automaticOnOffKeepalives) {
        mExecutor.execute(() -> {
            try {
                mService.startNattKeepaliveWithFd(mNetwork, mPfd, mResourceId,
                        intervalSec, mCallback, mSource.getHostAddress(),
                        mDestination.getHostAddress(), automaticOnOffKeepalives);
            } catch (RemoteException e) {
                Log.e(TAG, "Error starting socket keepalive: ", e);
                throw e.rethrowFromSystemServer();
            }
        });
    }

    @Override
    protected void stopImpl() {
        mExecutor.execute(() -> {
            try {
                if (mSlot != null) {
                    mService.stopKeepalive(mNetwork, mSlot);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error stopping socket keepalive: ", e);
                throw e.rethrowFromSystemServer();
            }
        });
    }
}
