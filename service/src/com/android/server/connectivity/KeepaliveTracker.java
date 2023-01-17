/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NattSocketKeepalive.NATT_PORT;
import static android.net.NetworkAgent.CMD_START_SOCKET_KEEPALIVE;
import static android.net.SocketKeepalive.BINDER_DIED;
import static android.net.SocketKeepalive.DATA_RECEIVED;
import static android.net.SocketKeepalive.ERROR_INSUFFICIENT_RESOURCES;
import static android.net.SocketKeepalive.ERROR_INVALID_INTERVAL;
import static android.net.SocketKeepalive.ERROR_INVALID_IP_ADDRESS;
import static android.net.SocketKeepalive.ERROR_INVALID_NETWORK;
import static android.net.SocketKeepalive.ERROR_INVALID_SOCKET;
import static android.net.SocketKeepalive.ERROR_NO_SUCH_SLOT;
import static android.net.SocketKeepalive.ERROR_STOP_REASON_UNINITIALIZED;
import static android.net.SocketKeepalive.ERROR_UNSUPPORTED;
import static android.net.SocketKeepalive.MAX_INTERVAL_SEC;
import static android.net.SocketKeepalive.MIN_INTERVAL_SEC;
import static android.net.SocketKeepalive.NO_KEEPALIVE;
import static android.net.SocketKeepalive.SUCCESS;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import static com.android.net.module.util.netlink.NetlinkConstants.NLMSG_DONE;
import static com.android.net.module.util.netlink.NetlinkConstants.SOCKDIAG_MSG_HEADER_SIZE;
import static com.android.net.module.util.netlink.NetlinkConstants.SOCK_DIAG_BY_FAMILY;
import static com.android.net.module.util.netlink.NetlinkUtils.IO_TIMEOUT_MS;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityResources;
import android.net.INetd;
import android.net.ISocketKeepaliveCallback;
import android.net.InetAddresses;
import android.net.InvalidPacketException;
import android.net.KeepalivePacketData;
import android.net.MarkMaskParcel;
import android.net.NattKeepalivePacketData;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.SocketKeepalive.InvalidSocketException;
import android.net.TcpKeepalivePacketData;
import android.net.util.KeepaliveUtils;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.HexDump;
import com.android.net.module.util.IpUtils;
import com.android.net.module.util.SocketUtils;
import com.android.net.module.util.netlink.InetDiagMessage;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.StructNlAttr;
import com.android.server.NetIdManager;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

/**
 * Manages socket keepalive requests.
 *
 * Provides methods to stop and start keepalive requests, and keeps track of keepalives across all
 * networks. This class is tightly coupled to ConnectivityService. It is not thread-safe and its
 * handle* methods must be called only from the ConnectivityService handler thread.
 */
public class KeepaliveTracker {

    private static final String TAG = "KeepaliveTracker";
    private static final boolean DBG = false;

    public static final String PERMISSION = android.Manifest.permission.PACKET_KEEPALIVE_OFFLOAD;
    private static final int[] ADDRESS_FAMILIES = new int[] {AF_INET6, AF_INET};

    private static final String ACTION_TCP_POLLING_ALARM =
            "com.android.server.connectivity.KeepaliveTracker.TCP_POLLING_ALARM";
    private static final String EXTRA_NETWORK = "network_id";
    private static final String EXTRA_SLOT = "slot";
    private static final long DEFAULT_TCP_POLLING_INTERVAL_MS = 120_000L;
    private static final int STATE_DISABLED = 0;
    private static final int STATE_ENABLED = 1;
    private static final int STATE_SUSPENDED = 2;
    private static final String AUTOMATIC_ON_OFF_KEEPALIVE_MODE = "automatic_on_off_keepalive_mode";
    private static final int AUTOMATIC_ON_OFF_KEEPALIVE_MODE_DISABLED = 0;
    private static final int AUTOMATIC_ON_OFF_KEEPALIVE_MODE_ENABLED = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STATE_" }, value = {
        STATE_DISABLED,
        STATE_ENABLED,
        STATE_SUSPENDED
    })
    private @interface AutomaticOnOffStatus {}

    /** Keeps track of keepalive requests. */
    private final HashMap <NetworkAgentInfo, HashMap<Integer, KeepaliveInfo>> mKeepalives =
            new HashMap<> ();
    /**
     * Keeps track of automatic on/off keepalive requests.
     * This should be only updated in ConnectivityService handler thread.
     */
    private final ArrayList<AutomaticOnOffKeepalive> mAutomaticOnOffKeepalives = new ArrayList<>();
    private final Handler mConnectivityServiceHandler;
    @NonNull
    private final TcpKeepaliveController mTcpController;
    @NonNull
    private final Context mContext;

    // Supported keepalive count for each transport type, can be configured through
    // config_networkSupportedKeepaliveCount. For better error handling, use
    // {@link getSupportedKeepalivesForNetworkCapabilities} instead of direct access.
    @NonNull
    private final int[] mSupportedKeepalives;

    // Reserved privileged keepalive slots per transport. Caller's permission will be enforced if
    // the number of remaining keepalive slots is less than or equal to the threshold.
    private final int mReservedPrivilegedSlots;

    // Allowed unprivileged keepalive slots per uid. Caller's permission will be enforced if
    // the number of remaining keepalive slots is less than or equal to the threshold.
    private final int mAllowedUnprivilegedSlotsForUid;
    /**
     * The {@Code inetDiagReqV2} messages for different IP family.
     *
     *   Key: Ip family type.
     * Value: Bytes array represent the {@Code inetDiagReqV2}.
     *
     * This should only be accessed in the connectivity service handler thread.
     */
    private final SparseArray<byte[]> mSockDiagMsg = new SparseArray<>();
    private final Dependencies mDependencies;
    private final INetd mNetd;
    private final AlarmManager mAlarmManager;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_TCP_POLLING_ALARM.equals(intent.getAction())) {
                Log.d(TAG, "Received TCP polling intent");
                final Network network = intent.getParcelableExtra(EXTRA_NETWORK);
                final int slot = intent.getIntExtra(EXTRA_SLOT, -1);
                mConnectivityServiceHandler.obtainMessage(
                        NetworkAgent.CMD_POLL_TCP_SOCKETS, slot, 0 , network).sendToTarget();
            }
        }
    };

    public KeepaliveTracker(Context context, Handler handler) {
        this(context, handler, new Dependencies(context));
    }

    @VisibleForTesting
    public KeepaliveTracker(Context context, Handler handler, Dependencies dependencies) {
        mConnectivityServiceHandler = handler;
        mTcpController = new TcpKeepaliveController(handler);
        mContext = context;
        mDependencies = dependencies;
        mSupportedKeepalives = mDependencies.getSupportedKeepalives();
        mNetd = mDependencies.getNetd();

        final Resources res = mDependencies.newConnectivityResources();
        mReservedPrivilegedSlots = res.getInteger(
                R.integer.config_reservedPrivilegedKeepaliveSlots);
        mAllowedUnprivilegedSlotsForUid = res.getInteger(
                R.integer.config_allowedUnprivilegedKeepalivePerUid);
        if (SdkLevel.isAtLeastU()) {
            mContext.registerReceiver(mReceiver, new IntentFilter(ACTION_TCP_POLLING_ALARM),
                    null, handler);
        }
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
    }

    /**
     * Tracks information about a socket keepalive.
     *
     * All information about this keepalive is known at construction time except the slot number,
     * which is only returned when the hardware has successfully started the keepalive.
     */
    class KeepaliveInfo implements IBinder.DeathRecipient {
        // Bookkeeping data.
        private final ISocketKeepaliveCallback mCallback;
        private final int mUid;
        private final int mPid;
        private final boolean mPrivileged;
        private final NetworkAgentInfo mNai;
        private final int mType;
        private final FileDescriptor mFd;

        public static final int TYPE_NATT = 1;
        public static final int TYPE_TCP = 2;

        // Keepalive slot. A small integer that identifies this keepalive among the ones handled
        // by this network.
        private int mSlot = NO_KEEPALIVE;

        // Packet data.
        private final KeepalivePacketData mPacket;
        private final int mInterval;

        // Whether the keepalive is started or not. The initial state is NOT_STARTED.
        private static final int NOT_STARTED = 1;
        private static final int STARTING = 2;
        private static final int STARTED = 3;
        private static final int STOPPING = 4;
        private int mStartedState = NOT_STARTED;
        private int mStopReason = ERROR_STOP_REASON_UNINITIALIZED;

        KeepaliveInfo(@NonNull ISocketKeepaliveCallback callback,
                @NonNull NetworkAgentInfo nai,
                @NonNull KeepalivePacketData packet,
                int interval,
                int type,
                @Nullable FileDescriptor fd) throws InvalidSocketException {
            mCallback = callback;
            mPid = Binder.getCallingPid();
            mUid = Binder.getCallingUid();
            mPrivileged = (PERMISSION_GRANTED == mContext.checkPermission(PERMISSION, mPid, mUid));

            mNai = nai;
            mPacket = packet;
            mInterval = interval;
            mType = type;

            // For SocketKeepalive, a dup of fd is kept in mFd so the source port from which the
            // keepalives are sent cannot be reused by another app even if the fd gets closed by
            // the user. A null is acceptable here for backward compatibility of PacketKeepalive
            // API.
            try {
                if (fd != null) {
                    mFd = Os.dup(fd);
                }  else {
                    Log.d(TAG, toString() + " calls with null fd");
                    if (!mPrivileged) {
                        throw new SecurityException(
                                "null fd is not allowed for unprivileged access.");
                    }
                    if (mType == TYPE_TCP) {
                        throw new IllegalArgumentException(
                                "null fd is not allowed for tcp socket keepalives.");
                    }
                    mFd = null;
                }
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot dup fd: ", e);
                throw new InvalidSocketException(ERROR_INVALID_SOCKET, e);
            }

            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        public NetworkAgentInfo getNai() {
            return mNai;
        }

        private String startedStateString(final int state) {
            switch (state) {
                case NOT_STARTED : return "NOT_STARTED";
                case STARTING : return "STARTING";
                case STARTED : return "STARTED";
                case STOPPING : return "STOPPING";
            }
            throw new IllegalArgumentException("Unknown state");
        }

        public String toString() {
            return "KeepaliveInfo ["
                    + " type=" + mType
                    + " network=" + mNai.network
                    + " startedState=" + startedStateString(mStartedState)
                    + " "
                    + IpUtils.addressAndPortToString(mPacket.getSrcAddress(), mPacket.getSrcPort())
                    + "->"
                    + IpUtils.addressAndPortToString(mPacket.getDstAddress(), mPacket.getDstPort())
                    + " interval=" + mInterval
                    + " uid=" + mUid + " pid=" + mPid + " privileged=" + mPrivileged
                    + " packetData=" + HexDump.toHexString(mPacket.getPacket())
                    + " ]";
        }

        /** Called when the application process is killed. */
        public void binderDied() {
            stop(BINDER_DIED);
        }

        void unlinkDeathRecipient() {
            if (mCallback != null) {
                mCallback.asBinder().unlinkToDeath(this, 0);
            }
        }

        private int checkNetworkConnected() {
            if (!mNai.networkInfo.isConnectedOrConnecting()) {
                return ERROR_INVALID_NETWORK;
            }
            return SUCCESS;
        }

        private int checkSourceAddress() {
            // Check that we have the source address.
            for (InetAddress address : mNai.linkProperties.getAddresses()) {
                if (address.equals(mPacket.getSrcAddress())) {
                    return SUCCESS;
                }
            }
            return ERROR_INVALID_IP_ADDRESS;
        }

        private int checkInterval() {
            if (mInterval < MIN_INTERVAL_SEC || mInterval > MAX_INTERVAL_SEC) {
                return ERROR_INVALID_INTERVAL;
            }
            return SUCCESS;
        }

        private int checkPermission() {
            final HashMap<Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(mNai);
            if (networkKeepalives == null) {
                return ERROR_INVALID_NETWORK;
            }

            if (mPrivileged) return SUCCESS;

            final int supported = KeepaliveUtils.getSupportedKeepalivesForNetworkCapabilities(
                    mSupportedKeepalives, mNai.networkCapabilities);

            int takenUnprivilegedSlots = 0;
            for (final KeepaliveInfo ki : networkKeepalives.values()) {
                if (!ki.mPrivileged) ++takenUnprivilegedSlots;
            }
            if (takenUnprivilegedSlots > supported - mReservedPrivilegedSlots) {
                return ERROR_INSUFFICIENT_RESOURCES;
            }

            // Count unprivileged keepalives for the same uid across networks.
            int unprivilegedCountSameUid = 0;
            for (final HashMap<Integer, KeepaliveInfo> kaForNetwork : mKeepalives.values()) {
                for (final KeepaliveInfo ki : kaForNetwork.values()) {
                    if (ki.mUid == mUid) {
                        unprivilegedCountSameUid++;
                    }
                }
            }
            if (unprivilegedCountSameUid > mAllowedUnprivilegedSlotsForUid) {
                return ERROR_INSUFFICIENT_RESOURCES;
            }
            return SUCCESS;
        }

        private int checkLimit() {
            final HashMap<Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(mNai);
            if (networkKeepalives == null) {
                return ERROR_INVALID_NETWORK;
            }
            final int supported = KeepaliveUtils.getSupportedKeepalivesForNetworkCapabilities(
                    mSupportedKeepalives, mNai.networkCapabilities);
            if (supported == 0) return ERROR_UNSUPPORTED;
            if (networkKeepalives.size() > supported) return ERROR_INSUFFICIENT_RESOURCES;
            return SUCCESS;
        }

        private int isValid() {
            synchronized (mNai) {
                int error = checkInterval();
                if (error == SUCCESS) error = checkLimit();
                if (error == SUCCESS) error = checkPermission();
                if (error == SUCCESS) error = checkNetworkConnected();
                if (error == SUCCESS) error = checkSourceAddress();
                return error;
            }
        }

        void start(int slot) {
            mSlot = slot;
            int error = isValid();
            if (error == SUCCESS) {
                Log.d(TAG, "Starting keepalive " + mSlot + " on " + mNai.toShortString());
                switch (mType) {
                    case TYPE_NATT:
                        final NattKeepalivePacketData nattData = (NattKeepalivePacketData) mPacket;
                        mNai.onAddNattKeepalivePacketFilter(slot, nattData);
                        mNai.onStartNattSocketKeepalive(slot, mInterval, nattData);
                        break;
                    case TYPE_TCP:
                        try {
                            mTcpController.startSocketMonitor(mFd, this, mSlot);
                        } catch (InvalidSocketException e) {
                            handleStopKeepalive(mNai, mSlot, ERROR_INVALID_SOCKET);
                            return;
                        }
                        final TcpKeepalivePacketData tcpData = (TcpKeepalivePacketData) mPacket;
                        mNai.onAddTcpKeepalivePacketFilter(slot, tcpData);
                        // TODO: check result from apf and notify of failure as needed.
                        mNai.onStartTcpSocketKeepalive(slot, mInterval, tcpData);
                        break;
                    default:
                        Log.wtf(TAG, "Starting keepalive with unknown type: " + mType);
                        handleStopKeepalive(mNai, mSlot, error);
                        return;
                }
                mStartedState = STARTING;
            } else {
                handleStopKeepalive(mNai, mSlot, error);
                return;
            }
        }

        void stop(int reason) {
            int uid = Binder.getCallingUid();
            if (uid != mUid && uid != Process.SYSTEM_UID) {
                if (DBG) {
                    Log.e(TAG, "Cannot stop unowned keepalive " + mSlot + " on " + mNai.network);
                }
            }
            // To prevent races from re-entrance of stop(), return if the state is already stopping.
            // This might happen if multiple event sources stop keepalive in a short time. Such as
            // network disconnect after user calls stop(), or tear down socket after binder died.
            if (mStartedState == STOPPING) return;

            // Store the reason of stopping, and report it after the keepalive is fully stopped.
            if (mStopReason != ERROR_STOP_REASON_UNINITIALIZED) {
                throw new IllegalStateException("Unexpected stop reason: " + mStopReason);
            }
            mStopReason = reason;
            Log.d(TAG, "Stopping keepalive " + mSlot + " on " + mNai.toShortString()
                    + ": " + reason);
            switch (mStartedState) {
                case NOT_STARTED:
                    // Remove the reference of the keepalive that meet error before starting,
                    // e.g. invalid parameter.
                    cleanupStoppedKeepalive(mNai, mSlot);
                    break;
                default:
                    mStartedState = STOPPING;
                    switch (mType) {
                        case TYPE_TCP:
                            mTcpController.stopSocketMonitor(mSlot);
                            // fall through
                        case TYPE_NATT:
                            mNai.onStopSocketKeepalive(mSlot);
                            mNai.onRemoveKeepalivePacketFilter(mSlot);
                            break;
                        default:
                            Log.wtf(TAG, "Stopping keepalive with unknown type: " + mType);
                    }
            }

            // Close the duplicated fd that maintains the lifecycle of socket whenever
            // keepalive is running.
            if (mFd != null) {
                try {
                    Os.close(mFd);
                } catch (ErrnoException e) {
                    // This should not happen since system server controls the lifecycle of fd when
                    // keepalive offload is running.
                    Log.wtf(TAG, "Error closing fd for keepalive " + mSlot + ": " + e);
                }
            }
        }

        void onFileDescriptorInitiatedStop(final int socketKeepaliveReason) {
            handleStopKeepalive(mNai, mSlot, socketKeepaliveReason);
        }
    }

    void notifyErrorCallback(ISocketKeepaliveCallback cb, int error) {
        if (DBG) Log.w(TAG, "Sending onError(" + error + ") callback");
        try {
            cb.onError(error);
        } catch (RemoteException e) {
            Log.w(TAG, "Discarded onError(" + error + ") callback");
        }
    }

    private  int findFirstFreeSlot(NetworkAgentInfo nai) {
        HashMap networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives == null) {
            networkKeepalives = new HashMap<Integer, KeepaliveInfo>();
            mKeepalives.put(nai, networkKeepalives);
        }

        // Find the lowest-numbered free slot. Slot numbers start from 1, because that's what two
        // separate chipset implementations independently came up with.
        int slot;
        for (slot = 1; slot <= networkKeepalives.size(); slot++) {
            if (networkKeepalives.get(slot) == null) {
                return slot;
            }
        }
        return slot;
    }

    public void handleStartKeepalive(Message message) {
        final KeepaliveInfo ki = (KeepaliveInfo) message.obj;
        final NetworkAgentInfo nai = ki.getNai();
        final int slot = findFirstFreeSlot(nai);
        mKeepalives.get(nai).put(slot, ki);
        ki.start(slot);

        // Add automatic on/off request into list to track its life cycle.
        final boolean automaticOnOff = message.arg1 != 0
                && (mDependencies.getDeviceConfigPropertyInt(NAMESPACE_CONNECTIVITY,
                AUTOMATIC_ON_OFF_KEEPALIVE_MODE, AUTOMATIC_ON_OFF_KEEPALIVE_MODE_ENABLED)
                != AUTOMATIC_ON_OFF_KEEPALIVE_MODE_DISABLED);
        if (automaticOnOff) {
            try {
                addOrReplaceAutomaticOnOffKeepalive(new AutomaticOnOffKeepalive(
                        nai.network(), slot, ki, mContext,
                        mConnectivityServiceHandler));
            } catch (InvalidSocketException | IllegalArgumentException e) {
                Log.e(TAG, "Fail to construct keepalive", e);
                notifyErrorCallback(ki.mCallback, ERROR_INVALID_SOCKET);
                return;
            }
        }
    }

    public void handleStopAllKeepalives(NetworkAgentInfo nai, int reason) {
        final HashMap<Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives != null) {
            final ArrayList<KeepaliveInfo> kalist = new ArrayList(networkKeepalives.values());
            for (KeepaliveInfo ki : kalist) {
                ki.stop(reason);
                // Clean up keepalives since the network agent is disconnected and unable to pass
                // back asynchronous result of stop().
                cleanupStoppedKeepalive(nai, ki.mSlot);
            }
        }
    }

    public void handleStopKeepalive(NetworkAgentInfo nai, int slot, int reason) {
        final String networkName = NetworkAgentInfo.toShortString(nai);
        HashMap <Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives == null) {
            Log.e(TAG, "Attempt to stop keepalive on nonexistent network " + networkName);
            return;
        }
        KeepaliveInfo ki = networkKeepalives.get(slot);
        if (ki == null) {
            Log.e(TAG, "Attempt to stop nonexistent keepalive " + slot + " on " + networkName);
            return;
        }
        ki.stop(reason);
        // Clean up keepalives will be done as a result of calling ki.stop() after the slots are
        // freed.
    }

    private void cleanupStoppedKeepalive(NetworkAgentInfo nai, int slot) {
        final String networkName = NetworkAgentInfo.toShortString(nai);
        HashMap<Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives == null) {
            Log.e(TAG, "Attempt to remove keepalive on nonexistent network " + networkName);
            return;
        }
        KeepaliveInfo ki = networkKeepalives.get(slot);
        if (ki == null) {
            Log.e(TAG, "Attempt to remove nonexistent keepalive " + slot + " on " + networkName);
            return;
        }

        maybeCloseFdAndCleanAutomaticOnOffKeepalive(nai.network(), slot);

        // Remove the keepalive from hash table so the slot can be considered available when reusing
        // it.
        networkKeepalives.remove(slot);
        Log.d(TAG, "Remove keepalive " + slot + " on " + networkName + ", "
                + networkKeepalives.size() + " remains.");
        if (networkKeepalives.isEmpty()) {
            mKeepalives.remove(nai);
        }

        // Notify app that the keepalive is stopped.
        final int reason = ki.mStopReason;
        if (reason == SUCCESS) {
            try {
                ki.mCallback.onStopped();
            } catch (RemoteException e) {
                Log.w(TAG, "Discarded onStop callback: " + reason);
            }
        } else if (reason == DATA_RECEIVED) {
            try {
                ki.mCallback.onDataReceived();
            } catch (RemoteException e) {
                Log.w(TAG, "Discarded onDataReceived callback: " + reason);
            }
        } else if (reason == ERROR_STOP_REASON_UNINITIALIZED) {
            throw new IllegalStateException("Unexpected stop reason: " + reason);
        } else if (reason == ERROR_NO_SUCH_SLOT) {
            throw new IllegalStateException("No such slot: " + reason);
        } else {
            notifyErrorCallback(ki.mCallback, reason);
        }

        ki.unlinkDeathRecipient();
    }

    public void handleCheckKeepalivesStillValid(NetworkAgentInfo nai) {
        HashMap <Integer, KeepaliveInfo> networkKeepalives = mKeepalives.get(nai);
        if (networkKeepalives != null) {
            ArrayList<Pair<Integer, Integer>> invalidKeepalives = new ArrayList<>();
            for (int slot : networkKeepalives.keySet()) {
                int error = networkKeepalives.get(slot).isValid();
                if (error != SUCCESS) {
                    invalidKeepalives.add(Pair.create(slot, error));
                }
            }
            for (Pair<Integer, Integer> slotAndError: invalidKeepalives) {
                handleStopKeepalive(nai, slotAndError.first, slotAndError.second);
            }
        }
    }

    /** Handle keepalive events from lower layer. */
    public void handleEventSocketKeepalive(@NonNull NetworkAgentInfo nai, int slot, int reason) {
        KeepaliveInfo ki = null;
        try {
            ki = mKeepalives.get(nai).get(slot);
        } catch(NullPointerException e) {}
        if (ki == null) {
            Log.e(TAG, "Event " + NetworkAgent.EVENT_SOCKET_KEEPALIVE + "," + slot + "," + reason
                    + " for unknown keepalive " + slot + " on " + nai.toShortString());
            return;
        }

        // This can be called in a number of situations :
        // - startedState is STARTING.
        //   - reason is SUCCESS => go to STARTED.
        //   - reason isn't SUCCESS => it's an error starting. Go to NOT_STARTED and stop keepalive.
        // - startedState is STARTED.
        //   - reason is SUCCESS => it's a success stopping. Go to NOT_STARTED and stop keepalive.
        //   - reason isn't SUCCESS => it's an error in exec. Go to NOT_STARTED and stop keepalive.
        // The control is not supposed to ever come here if the state is NOT_STARTED. This is
        // because in NOT_STARTED state, the code will switch to STARTING before sending messages
        // to start, and the only way to NOT_STARTED is this function, through the edges outlined
        // above : in all cases, keepalive gets stopped and can't restart without going into
        // STARTING as messages are ordered. This also depends on the hardware processing the
        // messages in order.
        // TODO : clarify this code and get rid of mStartedState. Using a StateMachine is an
        // option.
        if (KeepaliveInfo.STARTING == ki.mStartedState) {
            if (SUCCESS == reason) {
                // Keepalive successfully started.
                Log.d(TAG, "Started keepalive " + slot + " on " + nai.toShortString());
                ki.mStartedState = KeepaliveInfo.STARTED;
                try {
                    ki.mCallback.onStarted(slot);
                } catch (RemoteException e) {
                    Log.w(TAG, "Discarded onStarted(" + slot + ") callback");
                }
            } else {
                Log.d(TAG, "Failed to start keepalive " + slot + " on " + nai.toShortString()
                        + ": " + reason);
                // The message indicated some error trying to start: do call handleStopKeepalive.
                handleStopKeepalive(nai, slot, reason);
            }
        } else if (KeepaliveInfo.STOPPING == ki.mStartedState) {
            // The message indicated result of stopping : clean up keepalive slots.
            Log.d(TAG, "Stopped keepalive " + slot + " on " + nai.toShortString()
                    + " stopped: " + reason);
            ki.mStartedState = KeepaliveInfo.NOT_STARTED;
            cleanupStoppedKeepalive(nai, slot);
        } else {
            Log.wtf(TAG, "Event " + NetworkAgent.EVENT_SOCKET_KEEPALIVE + "," + slot + "," + reason
                    + " for keepalive in wrong state: " + ki.toString());
        }
    }

    /**
     * Called when requesting that keepalives be started on a IPsec NAT-T socket. See
     * {@link android.net.SocketKeepalive}.
     **/
    public void startNattKeepalive(@Nullable NetworkAgentInfo nai,
            @Nullable FileDescriptor fd,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb,
            @NonNull String srcAddrString,
            int srcPort,
            @NonNull String dstAddrString,
            int dstPort, boolean automaticOnOffKeepalives) {
        if (nai == null) {
            notifyErrorCallback(cb, ERROR_INVALID_NETWORK);
            return;
        }

        InetAddress srcAddress, dstAddress;
        try {
            srcAddress = InetAddresses.parseNumericAddress(srcAddrString);
            dstAddress = InetAddresses.parseNumericAddress(dstAddrString);
        } catch (IllegalArgumentException e) {
            notifyErrorCallback(cb, ERROR_INVALID_IP_ADDRESS);
            return;
        }

        KeepalivePacketData packet;
        try {
            packet = NattKeepalivePacketData.nattKeepalivePacket(
                    srcAddress, srcPort, dstAddress, NATT_PORT);
        } catch (InvalidPacketException e) {
            notifyErrorCallback(cb, e.getError());
            return;
        }
        KeepaliveInfo ki = null;
        try {
            ki = new KeepaliveInfo(cb, nai, packet, intervalSeconds,
                    KeepaliveInfo.TYPE_NATT, fd);
        } catch (InvalidSocketException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Fail to construct keepalive", e);
            notifyErrorCallback(cb, ERROR_INVALID_SOCKET);
            return;
        }
        Log.d(TAG, "Created keepalive: " + ki.toString());
        mConnectivityServiceHandler.obtainMessage(NetworkAgent.CMD_START_SOCKET_KEEPALIVE,
                automaticOnOffKeepalives ? 1 : 0,
                0, ki).sendToTarget();
    }

    /**
     * Called by ConnectivityService to start TCP keepalive on a file descriptor.
     *
     * In order to offload keepalive for application correctly, sequence number, ack number and
     * other fields are needed to form the keepalive packet. Thus, this function synchronously
     * puts the socket into repair mode to get the necessary information. After the socket has been
     * put into repair mode, the application cannot access the socket until reverted to normal.
     *
     * See {@link android.net.SocketKeepalive}.
     **/
    public void startTcpKeepalive(@Nullable NetworkAgentInfo nai,
            @NonNull FileDescriptor fd,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb) {
        if (nai == null) {
            notifyErrorCallback(cb, ERROR_INVALID_NETWORK);
            return;
        }

        final TcpKeepalivePacketData packet;
        try {
            packet = TcpKeepaliveController.getTcpKeepalivePacket(fd);
        } catch (InvalidSocketException e) {
            notifyErrorCallback(cb, e.error);
            return;
        } catch (InvalidPacketException e) {
            notifyErrorCallback(cb, e.getError());
            return;
        }
        KeepaliveInfo ki = null;
        try {
            ki = new KeepaliveInfo(cb, nai, packet, intervalSeconds,
                    KeepaliveInfo.TYPE_TCP, fd);
        } catch (InvalidSocketException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Fail to construct keepalive e=" + e);
            notifyErrorCallback(cb, ERROR_INVALID_SOCKET);
            return;
        }
        Log.d(TAG, "Created keepalive: " + ki.toString());
        mConnectivityServiceHandler.obtainMessage(CMD_START_SOCKET_KEEPALIVE, ki).sendToTarget();
    }

   /**
    * Called when requesting that keepalives be started on a IPsec NAT-T socket. This function is
    * identical to {@link #startNattKeepalive}, but also takes a {@code resourceId}, which is the
    * resource index bound to the {@link UdpEncapsulationSocket} when creating by
    * {@link com.android.server.IpSecService} to verify whether the given
    * {@link UdpEncapsulationSocket} is legitimate.
    **/
    public void startNattKeepalive(@Nullable NetworkAgentInfo nai,
            @Nullable FileDescriptor fd,
            int resourceId,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb,
            @NonNull String srcAddrString,
            @NonNull String dstAddrString,
            int dstPort,
            boolean automaticOnOffKeepalives) {
        // Ensure that the socket is created by IpSecService.
        if (!isNattKeepaliveSocketValid(fd, resourceId)) {
            notifyErrorCallback(cb, ERROR_INVALID_SOCKET);
        }

        // Get src port to adopt old API.
        int srcPort = 0;
        try {
            final SocketAddress srcSockAddr = Os.getsockname(fd);
            srcPort = ((InetSocketAddress) srcSockAddr).getPort();
        } catch (ErrnoException e) {
            notifyErrorCallback(cb, ERROR_INVALID_SOCKET);
        }

        // Forward request to old API.
        startNattKeepalive(nai, fd, intervalSeconds, cb, srcAddrString, srcPort,
                dstAddrString, dstPort, automaticOnOffKeepalives);
    }

    /**
     * Verify if the IPsec NAT-T file descriptor and resource Id hold for IPsec keepalive is valid.
     **/
    public static boolean isNattKeepaliveSocketValid(@Nullable FileDescriptor fd, int resourceId) {
        // TODO: 1. confirm whether the fd is called from system api or created by IpSecService.
        //       2. If the fd is created from the system api, check that it's bounded. And
        //          call dup to keep the fd open.
        //       3. If the fd is created from IpSecService, check if the resource ID is valid. And
        //          hold the resource needed in IpSecService.
        if (null == fd) {
            return false;
        }
        return true;
    }

    /**
     * Dump KeepaliveTracker state.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Supported Socket keepalives: " + Arrays.toString(mSupportedKeepalives));
        pw.println("Reserved Privileged keepalives: " + mReservedPrivilegedSlots);
        pw.println("Allowed Unprivileged keepalives per uid: " + mAllowedUnprivilegedSlotsForUid);
        pw.println("Socket keepalives:");
        pw.increaseIndent();
        for (NetworkAgentInfo nai : mKeepalives.keySet()) {
            pw.println(nai.toShortString());
            pw.increaseIndent();
            for (int slot : mKeepalives.get(nai).keySet()) {
                KeepaliveInfo ki = mKeepalives.get(nai).get(slot);
                pw.println(slot + ": " + ki.toString());
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    /**
     * Dependencies class for testing.
     */
    @VisibleForTesting
    public static class Dependencies {
        private final Context mContext;

        public Dependencies(final Context context) {
            mContext = context;
        }

        /**
         * Create a netlink socket connected to the kernel.
         *
         * @return fd the fileDescriptor of the socket.
         */
        public FileDescriptor makeSocketConnectedToNetlink()
                throws ErrnoException, SocketException {
            final FileDescriptor fd = NetlinkUtils.createNetLinkInetDiagSocket();
            NetlinkUtils.connectSocketToNetlink(fd);
            Os.setsockoptTimeval(fd, SOL_SOCKET, SO_SNDTIMEO,
                    StructTimeval.fromMillis(IO_TIMEOUT_MS));
            return fd;
        }

        /**
         * Send composed message request to kernel.
         *
         * The given FileDescriptor is expected to be created by
         * {@link #makeSocketConnectedToNetlink} or equivalent way.
         *
         * @param fd a netlink socket {@code FileDescriptor} connected to the kernel.
         * @param msg the byte array representing the request message to write to kernel.
         */
        public void sendNetlinkRequestToKernel(@NonNull final FileDescriptor fd,
                @NonNull final byte[] msg)
                throws ErrnoException, InterruptedIOException {
            Os.write(fd, msg, 0 /* byteOffset */, msg.length);
        }

        /**
         * Get an INetd connector.
         */
        public INetd getNetd() {
            return INetd.Stub.asInterface(
                    (IBinder) mContext.getSystemService(Context.NETD_SERVICE));
        }

        /**
         * Receive the response message from kernel via given {@code FileDescriptor}.
         * The usage should follow the {@code #sendNetlinkRequestToKernel} call with the same
         * FileDescriptor.
         *
         * The overall response may be large but the individual messages should not be
         * excessively large(8-16kB) because trying to get the kernel to return
         * everything in one big buffer is inefficient as it forces the kernel to allocate
         * large chunks of linearly physically contiguous memory. The usage should iterate the
         * call of this method until the end of the overall message.
         *
         * The default receiving buffer size should be small enough that it is always
         * processed within the {@link NetlinkUtils#IO_TIMEOUT_MS} timeout.
         */
        public ByteBuffer recvSockDiagResponse(@NonNull final FileDescriptor fd)
                throws ErrnoException, InterruptedIOException {
            return NetlinkUtils.recvMessage(
                    fd, NetlinkUtils.DEFAULT_RECV_BUFSIZE, NetlinkUtils.IO_TIMEOUT_MS);
        }

        /**
         * Read supported keepalive count for each transport type from overlay resource.
         */
        public int[] getSupportedKeepalives() {
            return KeepaliveUtils.getSupportedKeepalives(mContext);
        }

        /**
         * Construct a new Resource from a new ConnectivityResources.
         */
        public Resources newConnectivityResources() {
            final ConnectivityResources resources = new ConnectivityResources(mContext);
            return resources.get();
        }

        /**
         * Look up the value of a property in DeviceConfig.
         * @param namespace The namespace containing the property to look up.
         * @param name The name of the property to look up.
         * @param defaultValue The value to return if the property does not exist or has no non-null
         *                     value.
         * @return the corresponding value, or defaultValue if none exists.
         */
        public int getDeviceConfigPropertyInt(@NonNull String namespace, @NonNull String name,
                int defaultValue) {
            return DeviceConfigUtils.getDeviceConfigPropertyInt(namespace, name, defaultValue);
        }
    }

    private void ensureRunningOnHandlerThread() {
        if (mConnectivityServiceHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on handler thread: " + Thread.currentThread().getName());
        }
    }

    @VisibleForTesting
    boolean isAnyTcpSocketConnected(int netId) {
        FileDescriptor fd = null;

        try {
            fd = mDependencies.makeSocketConnectedToNetlink();

            // Get network mask
            final MarkMaskParcel parcel = mNetd.getFwmarkForNetwork(netId);
            final int networkMark = (parcel != null) ? parcel.mark : NetlinkUtils.UNKNOWN_MARK;
            final int networkMask = (parcel != null) ? parcel.mask : NetlinkUtils.NULL_MASK;

            // Send request for each IP family
            for (final int family : ADDRESS_FAMILIES) {
                if (isAnyTcpSocketConnectedForFamily(fd, family, networkMark, networkMask)) {
                    return true;
                }
            }
        } catch (ErrnoException | SocketException | InterruptedIOException | RemoteException e) {
            Log.e(TAG, "Fail to get socket info via netlink.", e);
        } finally {
            SocketUtils.closeSocketQuietly(fd);
        }

        return false;
    }

    private boolean isAnyTcpSocketConnectedForFamily(FileDescriptor fd, int family, int networkMark,
            int networkMask) throws ErrnoException, InterruptedIOException {
        ensureRunningOnHandlerThread();
        // Build SocketDiag messages and cache it.
        if (mSockDiagMsg.get(family) == null) {
            mSockDiagMsg.put(family, InetDiagMessage.buildInetDiagReqForAliveTcpSockets(family));
        }
        mDependencies.sendNetlinkRequestToKernel(fd, mSockDiagMsg.get(family));

        // Iteration limitation as a protection to avoid possible infinite loops.
        // DEFAULT_RECV_BUFSIZE could read more than 20 sockets per time. Max iteration
        // should be enough to go through reasonable TCP sockets in the device.
        final int maxIteration = 100;
        int parsingIteration = 0;
        while (parsingIteration < maxIteration) {
            final ByteBuffer bytes = mDependencies.recvSockDiagResponse(fd);

            try {
                while (NetlinkUtils.enoughBytesRemainForValidNlMsg(bytes)) {
                    final int startPos = bytes.position();

                    final int nlmsgLen = bytes.getInt();
                    final int nlmsgType = bytes.getShort();
                    if (isEndOfMessageOrError(nlmsgType)) return false;
                    // TODO: Parse InetDiagMessage to get uid and dst address information to filter
                    //  socket via NetlinkMessage.parse.

                    // Skip the header to move to data part.
                    bytes.position(startPos + SOCKDIAG_MSG_HEADER_SIZE);

                    if (isTargetTcpSocket(bytes, nlmsgLen, networkMark, networkMask)) {
                        return true;
                    }
                }
            } catch (BufferUnderflowException e) {
                // The exception happens in random place in either header position or any data
                // position. Partial bytes from the middle of the byte buffer may not be enough to
                // clarify, so print out the content before the error to possibly prevent printing
                // the whole 8K buffer.
                final int exceptionPos = bytes.position();
                final StringBuilder hex = new StringBuilder();
                for (int i = 0; i < exceptionPos; i++) {
                    final byte b = bytes.get(i);
                    hex.append(String.format("%02X", b & 0xff));
                }
                Log.e(TAG, "Unexpected socket info parsing: " + hex.toString(), e);
            }

            parsingIteration++;
        }
        return false;
    }

    private boolean isEndOfMessageOrError(int nlmsgType) {
        return nlmsgType == NLMSG_DONE || nlmsgType != SOCK_DIAG_BY_FAMILY;
    }

    private boolean isTargetTcpSocket(@NonNull ByteBuffer bytes, int nlmsgLen, int networkMark,
            int networkMask) {
        final int mark = readSocketDataAndReturnMark(bytes, nlmsgLen);
        return (mark & networkMask) == networkMark;
    }

    private int readSocketDataAndReturnMark(@NonNull ByteBuffer bytes, int nlmsgLen) {
        final int nextMsgOffset = bytes.position() + nlmsgLen - SOCKDIAG_MSG_HEADER_SIZE;

        try {
            // Get socket mark
            while (bytes.position() < nextMsgOffset) {
                final StructNlAttr nlattr = StructNlAttr.parse(bytes);
                if (nlattr != null && nlattr.nla_type == NetlinkUtils.INET_DIAG_MARK) {
                    return nlattr.getValueAsInteger();
                }
            }
        } finally {
            // Go through all data part
            bytes.position(nextMsgOffset);
        }
        return NetlinkUtils.INIT_MARK_VALUE;
    }

    // Class for keeping keepalive that will be automatically turned on/off by the system based
    // on TCP sockets status.
    private class AutomaticOnOffKeepalive {
        @NonNull
        public final Network network;
        public final int slot;
        @NonNull
        public final KeepaliveInfo ki;
        // A dup of fd that is kept the mFd in KeepaliveInfo to occupy the source port even when
        // the original KeepliaveInfo is closed.
        @NonNull
        public final FileDescriptor mFd;
        @NonNull
        private final PendingIntent mTcpPollingAlarm;
        @NonNull
        private final Handler mHandler;
        // Disabled when automatic on/off is no longer necessary.
        @AutomaticOnOffStatus
        private int mAutomaticOnOffStatus = STATE_ENABLED;

        AutomaticOnOffKeepalive(@NonNull Network network, int slot, @NonNull KeepaliveInfo ki,
                @NonNull Context context, @NonNull Handler handler)
                throws InvalidSocketException {
            Objects.requireNonNull(context);
            this.network = Objects.requireNonNull(network);
            this.ki = Objects.requireNonNull(ki);
            this.mHandler = Objects.requireNonNull(handler);
            this.slot = slot;

            // A null fd is acceptable in KeepaliveInfo for backward compatibility of
            // PacketKeepalive API, but it should not happen here because legacy API cannot setup
            // automatic keepalive.
            Objects.requireNonNull(ki.mFd);
            try {
                this.mFd = Os.dup(ki.mFd);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot dup fd: ", e);
                throw new InvalidSocketException(ERROR_INVALID_SOCKET, e);
            }

            mTcpPollingAlarm = createTcpPollingAlarmIntent(context, network, slot);
            startTcpPollingAlarm();
        }

        private void startTcpPollingAlarm() {
            final long triggerAtMillis =
                    SystemClock.elapsedRealtime() + DEFAULT_TCP_POLLING_INTERVAL_MS;
            // Setup a non-wake up alarm.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, triggerAtMillis,
                    mTcpPollingAlarm);
        }

        private PendingIntent createTcpPollingAlarmIntent(@NonNull Context context,
                @NonNull Network network, int slot) {
            final Intent intent = new Intent(ACTION_TCP_POLLING_ALARM);
            intent.putExtra(EXTRA_NETWORK, network);
            intent.putExtra(EXTRA_SLOT, slot);
            return PendingIntent.getBroadcast(
                    context, 0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE);
        }

        /**
         * Determine if disable or re-enable keepalive is needed or not based on TCP sockets status.
         */
        public void handleMonitorTcpConnections(int vpnNetId) {
            if (vpnNetId < NetIdManager.MIN_NET_ID) {
                // Stop keepalive and stop check TCP sockets status if there is no VPN available.
                // App should stop the keepliave when the VPN network is gone. It's possible here
                // if the VPN network is gone and apps stop the keepalive while the alarm had been
                // fired. Apps should restart another keepalive when next VPN network is brought up.
                final int oldStatus = mAutomaticOnOffStatus;
                mAutomaticOnOffStatus = STATE_DISABLED;
                switch (oldStatus) {
                    case STATE_ENABLED:
                        // No VPN network available. Keepalive is no longer necessary.
                        handleStopKeepalive(ki.mNai, slot, SUCCESS);
                        break;
                    case STATE_SUSPENDED:
                        // Keepalive is already closed because of automatic keepalive.
                        // No action here.
                        break;
                    default:
                        Log.e(TAG, "Unexpected automaticOnOff value(" + mAutomaticOnOffStatus
                                + ") in" + ki);
                        break;
                }
                FileUtils.closeQuietly(mFd);
                return;
            }

            if (!isAnyTcpSocketConnected(vpnNetId)) {
                // No TCP socket exists. Stop keepalive.
                //
                // Remain SUSPENDED if current state is suspended; DISABLED state should never
                // reach here.
                if (mAutomaticOnOffStatus == STATE_ENABLED) {
                    mAutomaticOnOffStatus = STATE_SUSPENDED;
                    handleStopKeepalive(ki.mNai, slot, SUCCESS);
                }
            } else {
                // Re-start keepalive if keepalive is stopped.
                if (ki.mStartedState == KeepaliveInfo.NOT_STARTED) {
                    KeepaliveInfo newKi;
                    try {
                        // Get fd from AutomaticOnOffKeepalive since the fd is original
                        // KeepaliveInfo should be closed.
                        newKi = new KeepaliveInfo(ki.mCallback, ki.mNai, ki.mPacket, ki.mInterval,
                                ki.mType, mFd);
                    } catch (InvalidSocketException | IllegalArgumentException
                            | SecurityException e) {
                        Log.e(TAG, "Fail to construct keepalive", e);
                        notifyErrorCallback(ki.mCallback, ERROR_INVALID_SOCKET);
                        return;
                    }
                    mAutomaticOnOffStatus = STATE_ENABLED;
                    handleStartKeepalive(mHandler.obtainMessage(
                            NetworkAgent.CMD_START_SOCKET_KEEPALIVE,
                            mAutomaticOnOffStatus, 0, newKi));
                }
            }
            // TODO: listen to socket status instead of periodically check.
            if (mAutomaticOnOffStatus != STATE_DISABLED) {
                startTcpPollingAlarm();
            }
        }

        public boolean match(Network network, int slot) {
            return this.network.equals(network) && this.slot == slot;
        }

        public boolean isKeepaliveSuspended() {
            return mAutomaticOnOffStatus == STATE_SUSPENDED;
        }
    }

    /**
     * Determine if disable or re-enable keepalive is needed or not based on TCP sockets status.
     */
    public void handleMonitorTcpConnections(Network network, int slot, int vpnNetId) {
        final AutomaticOnOffKeepalive autoKi = findAutomaticOnOffKeepalive(network, slot);
        // This may happen if the keepalive is removed by the apps, and the alarm is fired at the
        // same time.
        if (autoKi == null) return;
        autoKi.handleMonitorTcpConnections(vpnNetId);
    }

    private void addOrReplaceAutomaticOnOffKeepalive(@NonNull AutomaticOnOffKeepalive ki) {
        final int index = findAutomaticOnOffKeepaliveIndex(ki.network, ki.slot);
        if (index >= 0) {
            mAutomaticOnOffKeepalives.set(index, ki);
        } else {
            mAutomaticOnOffKeepalives.add(ki);
        }
    }

    @Nullable
    private int findAutomaticOnOffKeepaliveIndex(@NonNull Network network,
            int slot) {
        int index = 0;
        for (AutomaticOnOffKeepalive ki : mAutomaticOnOffKeepalives) {
            if (ki.match(network, slot)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private AutomaticOnOffKeepalive findAutomaticOnOffKeepalive(@NonNull Network network,
            int slot) {
        final int index = findAutomaticOnOffKeepaliveIndex(network, slot);
        return (index >= 0) ? mAutomaticOnOffKeepalives.get(index) : null;
    }

    private void cleanAutomaticOnOffKeepalive(@NonNull Network network, int slot) {
        mAutomaticOnOffKeepalives.removeIf(ki -> ki.match(network, slot));
    }

    private void maybeCloseFdAndCleanAutomaticOnOffKeepalive(@NonNull Network network, int slot) {
        final AutomaticOnOffKeepalive autoKi = findAutomaticOnOffKeepalive(network, slot);
        if (autoKi == null) return;

        // If the automaticOnOff is ENABLED, this is expected to be called by the apps to stop the
        // keepalive normally. If the automaticOnOff is DISABLED, this should be triggered from
        // handleMonitorTcpConnections indicating that the keepalive is no longer necessary because
        // of specific invalid conditions, e.g. the VPN network for the keepalive does not exist.
        // The keepalive should be removed from the list in both cases.
        if (!autoKi.isKeepaliveSuspended()) {
            // Close the duplicated fd that maintains the lifecycle of socket because the fd may
            // not be closed in automatic on/ff keepalive mode.
            FileUtils.closeQuietly(autoKi.ki.mFd);
            FileUtils.closeQuietly(autoKi.mFd);

            cleanAutomaticOnOffKeepalive(network, slot);
        }
    }
}
