/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.Handler;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class VendorConnectivityService {
    static {
        System.loadLibrary("service-connectivity");
    }

    private static final String TAG = VendorConnectivityService.class.getSimpleName();
    private static final String BLOCKED_PORTS_MAP_PATH = makeMapPath("blocked_ports");

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/connectivity/map_offload_connectivity_" + which + "_map";
    }

    @NonNull
    private final Handler mHandler;
    @NonNull
    private final SharedLog mLog;
    @NonNull
    private final Dependencies mDeps;

    private final boolean mIsBpfEnabled;

    public static final int CMD_BLOCK_PORT = 1;

    public static final int CMD_UNBLOCK_PORT = 2;

    public static final int CMD_UNBLOCK_ALL_PORTS = 3;

    public static final int CMD_GET_BLOCKED_PORTS = 4;


    // BPF map for downstream IPv4 forwarding.
    @Nullable
    private final BpfMap<PortBlockKey, PortBlockKey> mBpfBlockedPortsMap;

    @VisibleForTesting
    public abstract static class Dependencies {
        /** Get handler. */
        @NonNull public abstract Handler getHandler();

        /** Get shared log. */
        @NonNull public abstract SharedLog getSharedLog();

        /**
         * Check OS Build at least T.
         */
        public boolean isAtLeastT() {
            return SdkLevel.isAtLeastT();
        }

        /** Get downstream6 BPF map. */
        @Nullable public BpfMap<PortBlockKey, PortBlockKey> getBlockPortsMap() {
            if (!isAtLeastT()) return null;
            try {
                return new BpfMap<>(BLOCKED_PORTS_MAP_PATH,
                    BpfMap.BPF_F_RDWR, PortBlockKey.class, PortBlockKey.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create downstream6 map: " + e);
                return null;
            }
        }

        // TODO: what other methods should be added for testing?
    }

    @VisibleForTesting
    public VendorConnectivityService(@NonNull Dependencies deps) {
        mDeps = deps;
        mHandler = mDeps.getHandler();
        mLog = mDeps.getSharedLog().forSubComponent(TAG);
        mIsBpfEnabled = isBpfEnabled();
        mBpfBlockedPortsMap.getBlockPortsMap();
    }

    private static class VendorConnectivityBinder extends VendorConnectivity.Stub {
        private static final String LOG_TAG = VendorConnectivityBinder.class.getSimpleName();

        private final Handler mHandler;

        private VendorConnectivityBinder(Handler handler) {
            mHandler = handler;
        }

        @Override
        void blockPort(in android.net.PortBlockParcel port) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_BLOCK_PORT), port);
        }

        @Override
        void unblockPort(in android.net.PortBlockParcel port) {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_UNBLOCK_PORT), port);
        }

        @Override
        void unblockAllPorts() {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_UNBLOCK_ALL_PORTS));
        }

        @Override
        android.net.PortBlockParcel[] getBlockedPorts() {
            mHandler.sendMessage(mHandler.obtainMessage(CMD_GET_BLOCKED_PORTS));
        }
    }

    private class VendorConnectivityServiceHandler extends Handler {
        VendorConnectivityServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CMD_BLOCK_PORT: {
                    addBlockPortRule(msg.arg1.port /* port */, msg.arg2.ipType /* ipType */,
                            msg.arg3.proto /* protocol */);
                    break;
                }
                case CMD_UNBLOCK_PORT: {
                    removeBlockPortRule(msg.arg1.port /* port */, msg.arg2.ipType /* ipType */,
                            msg.arg3.proto /* protocol */);
                    break;
                }
                case CMD_UNBLOCK_ALL_PORTS: {
                    clearBlockPortRules();
                    break;
                }
                case CMD_GET_BLOCKED_PORTS: {
                    // TODO: how to return list from here?
                    break;
                }
            }
        }
    }

    private boolean isUsingBpf() {
        return mIsBpfEnabled;
    }

    /**
     *
     */
    public void addBlockPortRule(int port, int ipType, int proto) {
        if (!isUsingBpf()) return;

        PortBlockKey key = new PortBlockKey(port, ipType, proto);
        PortBlockKey val = new PortBlockKey(port, ipType, proto);
        try {
            mBpfBlockedPortsMap.insertEntry(key, value);
        } catch (ErrnoException e) {
            mLog.e("Could not insert entry (" + key + ", " + value + "): " + e);
        } catch (IllegalStateException e) {
            // Silent if the rule already exists. Note that the errno EEXIST was rethrown as
            // IllegalStateException. See BpfMap#insertEntry.
        }
    }

    /**
     *
     */
    public void removeBlockPortRule(int port, int ipType, int proto) {
        if (!isUsingBpf()) return;

        PortBlockKey key = new PortBlockKey(port, ipType, proto);
        try {
            mBpfBlockedPortsMap.deleteEntry(key);
        } catch (ErrnoException e) {
            mLog.e("Could not delete entry (key: " + key + ")", e);
        }
    }

    /**
     *
     */
    public void clearBlockPortRules() {
        if (!isUsingBpf()) return;

        try {
            mBpfBlockedPortsMap.clear();
        } catch (ErrnoException e) {
            mLog.e("Could not clear map", e);
        }
    }

    /**
     * Attach BPF program
     *
     * TODO: consider error handling if the attach program failed.
     */
    public void maybeAttachProgram(@NonNull String intIface, @NonNull String extIface) {
        try {
            // TODO: is extIface necessary to attach program to
            BpfUtils.attachProgram(intIface, false);
            BpfUtils.attachProgram(extIface, true);
        } catch (IOException e) {
            mLog.e("Could not detach program: " + e);
        }
    }

    /**
     * Detach BPF program
     */
    public void maybeDetachProgram(@NonNull String intIface, @NonNull String extIface) {
        try {
            // TODO: is extIface necessary to attach program to
            BpfUtils.detachProgram(intIface);
            BpfUtils.detachProgram(extIface);
        } catch (IOException e) {
            mLog.e("Could not detach program: " + e);
        }
    }

    /**
     * Dump information.
     * Block the function until all the data are dumped on the handler thread or timed-out. The
     * reason is that dumpsys invokes this function on the thread of caller and the data may only
     * be allowed to be accessed on the handler thread.
     */
    public void dump(@NonNull IndentingPrintWriter pw) {

    }

    private boolean isBpfEnabled() {
        // TODO: get from dep config
        return true;
    }
}