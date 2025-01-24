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

package com.android.server;

import static android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE;
import static android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN;
import static android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_ANY;
import static android.net.L2capNetworkSpecifier.PSM_ANY;
import static android.net.L2capNetworkSpecifier.ROLE_SERVER;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.system.OsConstants.AF_UNIX;
import static android.system.OsConstants.F_GETFL;
import static android.system.OsConstants.F_SETFL;
import static android.system.OsConstants.SO_TYPE;
import static android.system.OsConstants.SOCK_SEQPACKET;
import static android.system.OsConstants.SOL_SOCKET;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.L2capNetworkSpecifier;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkProvider.NetworkOfferCallback;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.NetworkSpecifier;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.ServiceConnectivityJni;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

public class L2capNetworkProvider {
    private static final String TAG = L2capNetworkProvider.class.getSimpleName();
    // Note: IFNAMSIZ is 16.
    private static final String TUN_IFNAME = "l2cap-tun";
    private static final int SO_PROTOCOL = 38;
    private static final int SO_DOMAIN = 39;

    private final Dependencies mDeps;
    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final NetworkProvider mProvider;
    private final BlanketReservationOffer mBlanketOffer;
    private final Map<Integer, ReservedServerOffer> mReservedServerOffers = new ArrayMap<>();
    // mBluetoothAdapter guaranteed non-null when read on handler thread after start() is called
    @Nullable
    private BluetoothAdapter mBluetoothAdapter;
    private int mTunIndex = 0;

    @Nullable
    private static ParcelFileDescriptor createTunInterface(String ifname) {
        final ParcelFileDescriptor fd;
        try {
            fd = ParcelFileDescriptor.adoptFd(
                    ServiceConnectivityJni.createTunTap(
                            true /*isTun*/, true /*hasCarrier*/, true /*setIffMulticast*/, ifname));
            ServiceConnectivityJni.bringUpInterface(ifname);
            // TODO: consider adding a parameter to createTunTap() to make i/o blocking.
            // L2capPacketForward uses two threads and blocking i/o.
            final int flags = Os.fcntlInt(fd.getFileDescriptor(), F_GETFL, 0);
            Os.fcntlInt(fd.getFileDescriptor(), F_SETFL, flags);
        } catch (Exception e) {
            // TODO: native functions should throw IOException.
            Log.e(TAG, "Failed to create tun interface", e);
            return null;
        }
        return fd;
    }

    private String getNextIfname() {
        return TUN_IFNAME + String.valueOf(mTunIndex++);
    }

    /**
     * The blanket reservation offer is used to create an L2CAP server network, i.e. a network
     * based on a BluetoothServerSocket.
     *
     * Note that NetworkCapabilities matching semantics will cause onNetworkNeeded to be called for
     * requests that do not have a NetworkSpecifier set.
     */
    private class BlanketReservationOffer implements NetworkOfferCallback {
        // TODO: ensure that once the incoming request is satisfied, the blanket offer does not get
        // unneeded. This means the blanket offer must always outscore the reserved offer. This
        // might require setting the blanket offer as setTransportPrimary().
        public static final NetworkScore SCORE = new NetworkScore.Builder().build();
        // Note the missing NET_CAPABILITY_NOT_RESTRICTED marking the network as restricted.
        public static final NetworkCapabilities CAPABILITIES;
        static {
            final L2capNetworkSpecifier l2capNetworkSpecifier = new L2capNetworkSpecifier.Builder()
                    .setRole(ROLE_SERVER)
                    .build();
            NetworkCapabilities caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                    .addTransportType(TRANSPORT_BLUETOOTH)
                    // TODO: consider removing NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED.
                    .addCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_METERED)
                    .addCapability(NET_CAPABILITY_NOT_ROAMING)
                    .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                    .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                    .addCapability(NET_CAPABILITY_NOT_VPN)
                    .setNetworkSpecifier(l2capNetworkSpecifier)
                    .build();
            caps.setReservationId(RES_ID_MATCH_ALL_RESERVATIONS);
            CAPABILITIES = caps;
        }

        // TODO: consider moving this into L2capNetworkSpecifier as #isValidServerReservation().
        private boolean isValidL2capSpecifier(@Nullable NetworkSpecifier spec) {
            if (spec == null) return false;
            // If spec is not null, L2capNetworkSpecifier#canBeSatisfiedBy() guarantees the
            // specifier is of type L2capNetworkSpecifier.
            final L2capNetworkSpecifier l2capSpec = (L2capNetworkSpecifier) spec;

            // The ROLE_SERVER offer can be satisfied by a ROLE_ANY request.
            if (l2capSpec.getRole() != ROLE_SERVER) return false;

            // HEADER_COMPRESSION_ANY is never valid in a request.
            if (l2capSpec.getHeaderCompression() == HEADER_COMPRESSION_ANY) return false;

            // remoteAddr must be null for ROLE_SERVER requests.
            if (l2capSpec.getRemoteAddress() != null) return false;

            // reservation must allocate a PSM, so only PSM_ANY can be passed.
            if (l2capSpec.getPsm() != PSM_ANY) return false;

            return true;
        }

        @Override
        public void onNetworkNeeded(NetworkRequest request) {
            Log.d(TAG, "New reservation request: " + request);
            if (!isValidL2capSpecifier(request.getNetworkSpecifier())) {
                Log.w(TAG, "Ignoring invalid reservation request: " + request);
                return;
            }

            final NetworkCapabilities reservationCaps = request.networkCapabilities;
            final ReservedServerOffer reservedOffer;
            try {
                reservedOffer = new ReservedServerOffer(reservationCaps);
            } catch (IOException e) {
                // Something went wrong when creating the offer. Send onUnavailable() to the app.
                Log.e(TAG, "Failed to create L2cap server offer", e);
                mProvider.declareNetworkRequestUnfulfillable(request);
                return;
            }

            final NetworkCapabilities reservedCaps = reservedOffer.getReservedCapabilities();
            mProvider.registerNetworkOffer(SCORE, reservedCaps, mHandler::post, reservedOffer);
            mReservedServerOffers.put(request.requestId, reservedOffer);
        }

        @Override
        public void onNetworkUnneeded(NetworkRequest request) {
            if (!mReservedServerOffers.containsKey(request.requestId)) {
                return;
            }

            final ReservedServerOffer reservedOffer = mReservedServerOffers.get(request.requestId);
            // Note that the reserved offer gets torn down when the reservation goes away, even if
            // there are lingering requests.
            reservedOffer.tearDown();
            mProvider.unregisterNetworkOffer(reservedOffer);
        }
    }

    /** Container that starts / stops packet forwarding and maintains NetworkAgent and IpClient. */
    private static class L2capNetwork {
        public interface Callback {
            public void onStopped();
            // TODO: consider adding onError() or StoppedReason to let the state object tear down
            // the offer if an error is encountered.
        }

        private final String mIfname;
        private final ParcelFileDescriptor mTunFd;
        private final ParcelFileDescriptor mL2capFd;
        // Keep BluetoothSocket around for cleanup.
        private final BluetoothSocket mBluetoothSocket;
        private final Callback mCb;

        private static boolean isSockSeqpacket(ParcelFileDescriptor l2capFd) {
            final FileDescriptor fd = l2capFd.getFileDescriptor();
            try {
                final int soDomain = Os.getsockoptInt(fd, SOL_SOCKET, SO_DOMAIN);
                if (soDomain != AF_UNIX) {
                    Log.wtf(TAG, "BluetoothSocket#mPfd unexpected SO_DOMAIN: " + soDomain);
                    return false;
                }
                final int soType = Os.getsockoptInt(fd, SOL_SOCKET, SO_TYPE);
                if (soType != SOCK_SEQPACKET) {
                    Log.wtf(TAG, "BluetoothSocket#mPfd unexpected SO_TYPE: " + soType);
                    return false;
                }
                final int soProtocol = Os.getsockoptInt(fd, SOL_SOCKET, SO_PROTOCOL);
                if (soProtocol != 0) {
                    Log.wtf(TAG, "BluetoothSocket#mPfd unexpected SO_PROTOCOL: " + soProtocol);
                    return false;
                }
            } catch (ErrnoException e) {
                Log.e(TAG, "getsockopt(BluetoothSocket#mPfd, SOL_SOCKET, ..) failed", e);
                return false;
            }
            return true;
        }

        @Nullable
        public static L2capNetwork create(String ifname, BluetoothSocket socket, Callback cb) {
            final ParcelFileDescriptor l2capFd;
            try {
                // TODO: expose a module lib API to get underlying PFD.
                final Field f = BluetoothSocket.class.getDeclaredField("mPfd");
                f.setAccessible(true);
                l2capFd = (ParcelFileDescriptor) f.get(socket);
            } catch (Exception e) {
                Log.wtf(TAG, "BluetoothSocket#mPfd is not accessible", e);
                return null;
            }

            if (!isSockSeqpacket(l2capFd)) {
                return null;
            }

            final ParcelFileDescriptor tunFd = createTunInterface(ifname);
            if (tunFd == null) {
                return null;
            }

            return new L2capNetwork(ifname, l2capFd, socket, tunFd, cb);
        }

        public L2capNetwork(String ifname, ParcelFileDescriptor l2capFd,
                BluetoothSocket socket, ParcelFileDescriptor tunFd, Callback cb) {
            mIfname = ifname;
            mL2capFd = l2capFd;
            // TODO: consider dup'ing l2capFd instead.
            mBluetoothSocket = socket;
            mTunFd = tunFd;
            mCb = cb;
        }

        public void tearDown() {
            try {
                mTunFd.close();
                mBluetoothSocket.close();
            } catch (IOException e) {
                Log.i(TAG, "Failed to tearDown L2capNetwork", e);
            }
        }
    }

    private class ReservedServerOffer implements NetworkOfferCallback {
        private final boolean mUseHeaderCompression;
        private final NetworkCapabilities mReservedCapabilities;
        private final BluetoothServerSocket mServerSocket;
        private final AcceptThread mAcceptThread;
        private final Map<String, L2capNetwork> mNetworkStates = new ArrayMap<>();

        private class AcceptThread extends Thread {
            private static final int TIMEOUT = 500;
            private final BluetoothServerSocket mServerSocket;
            private volatile boolean mIsRunning = true;

            public AcceptThread(BluetoothServerSocket serverSocket) {
                mServerSocket = serverSocket;
            }

            @Override
            public void run() {
                while (mIsRunning) {
                    try {
                        final BluetoothSocket acceptedSocket = mServerSocket.accept();
                        mHandler.post(() -> {
                            // Check whether the AcceptThread was stopped and the offer went away
                            if (!mIsRunning) return;
                            createNetworkState(acceptedSocket);
                        });
                    } catch (IOException e) {
                        // BluetoothSocket#accept throws IOException instead of InterruptedException
                        Log.i(TAG, "BluetoothServerSocket#accept interrupted", e);
                    }
                }
            }

            public void tearDown() {
                mIsRunning = false;
                interrupt();
                try {
                    join();
                } catch(InterruptedException e) {
                    Log.d(TAG, "join() was interrupted", e);
                }
            }
        }

        private void createNetworkState(BluetoothSocket socket) {
            // TODO: move inside of L2capNetwork.create()
            final String ifname = getNextIfname();
            final L2capNetwork networkState = L2capNetwork.create(ifname, socket,
                    new L2capNetwork.Callback() {
                // TODO: onStopped() could just take L2capNetwork.
                @Override
                public void onStopped() {
                    removeNetworkState(ifname);
                }
            });

            // If L2capNetwork failed to be created, close the BluetoothSocket and wait for
            // further connections.
            // TODO: consider tearing down the offer if this happens.
            if (networkState == null) {
                Log.i(TAG, "Failed to create L2capNetwork, tearing down incoming connection");
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.d(TAG, "Failed to close BluetoothSocket", e);
                }
                return;
            }
            mNetworkStates.put(ifname, networkState);
        }

        private void removeNetworkState(String ifname) {
            mNetworkStates.remove(ifname);
        }

        public ReservedServerOffer(NetworkCapabilities reservationCaps) throws IOException {
            // getNetworkSpecifier() is guaranteed to return a non-null L2capNetworkSpecifier.
            final L2capNetworkSpecifier reservationSpec =
                    (L2capNetworkSpecifier) reservationCaps.getNetworkSpecifier();
            mUseHeaderCompression =
                    reservationSpec.getHeaderCompression() == HEADER_COMPRESSION_6LOWPAN;

            // BluetoothAdapter is guaranteed to be non-null.
            // Throws an IOException on error.
            mServerSocket = mBluetoothAdapter.listenUsingInsecureL2capChannel();

            final L2capNetworkSpecifier reservedSpec = new L2capNetworkSpecifier.Builder()
                    .setRole(ROLE_SERVER)
                    .setHeaderCompression(reservationSpec.getHeaderCompression())
                    .setPsm(mServerSocket.getPsm())
                    .build();
            // TODO: should reserved capabilities be derived from request or blanket offer?
            mReservedCapabilities = new NetworkCapabilities.Builder(reservationCaps)
                    .setNetworkSpecifier(reservedSpec)
                    .build();

            mAcceptThread = new AcceptThread(mServerSocket);
            mAcceptThread.start();
        }

        public NetworkCapabilities getReservedCapabilities() {
            return mReservedCapabilities;
        }

        @Override
        public void onNetworkNeeded(NetworkRequest request) {
            // TODO: implement
        }

        @Override
        public void onNetworkUnneeded(NetworkRequest request) {
            // TODO: implement
        }

        /**
         * Called when the reservation goes away and the reserved offer must be torn down.
         *
         * This method can be called multiple times.
         */
        public void tearDown() {
            // This method can be called multiple times. TODO: Can it?
            mAcceptThread.tearDown();

            try {
                // BluetoothServerSocket#close() does not close any accepted BluetoothSockets and is
                // safe to be called multiple times.
                mServerSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close BluetoothServerSocket", e);
            }

            for (L2capNetwork state : mNetworkStates.values()) {
                // Closes the accepted BluetoothSocket.
                state.tearDown();
            }
        }
    }

    @VisibleForTesting
    public static class Dependencies {
        /** Get NetworkProvider */
        public NetworkProvider getNetworkProvider(Context context, Looper looper) {
            return new NetworkProvider(context, looper, TAG);
        }

        /** Get the HandlerThread for L2capNetworkProvider to run on */
        public HandlerThread getHandlerThread() {
            final HandlerThread thread = new HandlerThread("L2capNetworkProviderThread");
            thread.start();
            return thread;
        }
    }

    public L2capNetworkProvider(Context context) {
        this(new Dependencies(), context);
    }

    @VisibleForTesting
    public L2capNetworkProvider(Dependencies deps, Context context) {
        mDeps = deps;
        mContext = context;
        mHandlerThread = mDeps.getHandlerThread();
        mHandler = new Handler(mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(Message m) {

            }
        };
        mProvider = mDeps.getNetworkProvider(context, mHandlerThread.getLooper());
        mBlanketOffer = new BlanketReservationOffer();
    }

    /**
     * Start L2capNetworkProvider.
     *
     * Called on CS Handler thread.
     */
    public void start() {
        mHandler.post(() -> {
            final PackageManager pm = mContext.getPackageManager();
            if (!pm.hasSystemFeature(FEATURE_BLUETOOTH_LE)) {
                return;
            }
            final BluetoothManager bluetoothManager =
                    mContext.getSystemService(BluetoothManager.class);
            if (bluetoothManager == null) {
                // Can this ever happen?
                Log.wtf(TAG, "BluetoothManager not found");
                return;
            }
            mBluetoothAdapter = bluetoothManager.getAdapter();
            mContext.getSystemService(ConnectivityManager.class).registerNetworkProvider(mProvider);
            mProvider.registerNetworkOffer(BlanketReservationOffer.SCORE,
                    BlanketReservationOffer.CAPABILITIES, mHandler::post, mBlanketOffer);
        });
    }
}
