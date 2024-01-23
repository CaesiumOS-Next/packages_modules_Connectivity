/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.net;

import android.annotation.NonNull;
import android.content.Context;
import android.net.INetd;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.BaseNetdUnsolicitedEventListener;
import com.android.net.module.util.BpfDump;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.Struct.S32;

import java.io.File;
import java.io.IOException;
import java.lang.Thread;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Objects;

/**
 * Monitor interface added (without removed) and right interface name and its index to bpf map.
 */
public class BpfInterfaceMapUpdater {
    private static final String TAG = BpfInterfaceMapUpdater.class.getSimpleName();
    // This is current path but may be changed soon.
    private static final String IFACE_INDEX_NAME_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_iface_index_name_map";
    private final IBpfMap<S32, InterfaceMapValue> mIndexToIfaceBpfMap;
    private final INetd mNetd;
    private final Handler mHandler;
    private final Dependencies mDeps;

    public BpfInterfaceMapUpdater(Context ctx, Handler handler) {
        this(ctx, handler, new Dependencies());
    }

    @VisibleForTesting
    public BpfInterfaceMapUpdater(Context ctx, Handler handler, Dependencies deps) {
        mDeps = deps;
        mIndexToIfaceBpfMap = deps.getInterfaceMap();
        mNetd = deps.getINetd(ctx);
        mHandler = handler;
    }

    /**
     * Dependencies of BpfInerfaceMapUpdater, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /** Create BpfMap for updating interface and index mapping. */
        public IBpfMap<S32, InterfaceMapValue> getInterfaceMap() {
            try {
                return new BpfMap<>(IFACE_INDEX_NAME_MAP_PATH,
                    S32.class, InterfaceMapValue.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create interface map: " + e);
                return null;
            }
        }

        /** Get index for the given interface name. 0 if error. */
        public int getInterfaceIndex(@NonNull String ifaceName) {
            Objects.requireNonNull(ifaceName);
            // prevent spurious kernel network driver module loads for non-existing interfaces
            String p = "/sys/class/net/" + ifaceName + "/ifindex";
            File file = new File(p);
            if (!file.exists()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "sleep1 busted");
                }
                if (file.exists()) {
                    Log.e(TAG, "getInterfaceIndex for " + ifaceName + " called which *DID* not exist!!!");
                } else {
                    Log.e(TAG, "getInterfaceIndex for " + ifaceName + " called which still does not exist.");
                }
            }
            if (!file.exists()) return 0;
            int j = Os.if_nametoindex(ifaceName);
            if (j == 0) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "sleep2 busted");
                }
                j = Os.if_nametoindex(ifaceName);
                if (j == 0) {
                    Log.e(TAG, "if_nametoindex for " + ifaceName + " returned 0 twice (broken).");
                } else {
                    Log.e(TAG, "if_nametoindex for " + ifaceName + " returned 0 once (race fixed).");
                }
            }
            if (j == 0) {
                Path pth = Path.of(p);

//        try (SeekableByteChannel sbc = Files.newByteChannel(pth);
//             InputStream in = Channels.newInputStream(sbc)) {
//            byte[] b = read(in, 32);
//        }

                try {
                    String s = Files.newBufferedReader(pth).readLine();
                    j = Integer.parseInt(s);
                    Log.e(TAG, "readString " + pth + " returned " + s + " aka " + j);
                } catch (IOException e) {
                    Log.e(TAG, "ioEx " + e);
                }
            }
            return j;
        }

        /** Get INetd binder object. */
        public INetd getINetd(Context ctx) {
            return INetd.Stub.asInterface((IBinder) ctx.getSystemService(Context.NETD_SERVICE));
        }
    }

    /**
     * Start listening interface update event.
     * Query current interface names before listening.
     */
    public void start() {
        mHandler.post(() -> {
            if (mIndexToIfaceBpfMap == null) {
                Log.wtf(TAG, "Fail to start: Null bpf map");
                return;
            }

            try {
                // TODO: use a NetlinkMonitor and listen for RTM_NEWLINK messages instead.
                mNetd.registerUnsolicitedEventListener(new InterfaceChangeObserver());
            } catch (RemoteException e) {
                Log.wtf(TAG, "Unable to register netd UnsolicitedEventListener, " + e);
            }

            final String[] ifaces;
            try {
                // TODO: use a netlink dump to get the current interface list.
                ifaces = mNetd.interfaceGetList();
            } catch (RemoteException | ServiceSpecificException e) {
                Log.wtf(TAG, "Unable to query interface names by netd, " + e);
                return;
            }

            for (String ifaceName : ifaces) {
                addInterface(ifaceName);
            }
        });
    }

    private void addInterface(String ifaceName) {
        if (ifaceName == null) {
            Log.e(TAG, "Unable to addInterface for null interface name");
            return;
        }
        final int index = mDeps.getInterfaceIndex(ifaceName);
        if (index == 0) {
            Log.e(TAG, "Unable to get index for " + ifaceName);
            return;
        }
        try {
            mIndexToIfaceBpfMap.updateEntry(new S32(index), new InterfaceMapValue(ifaceName));
        } catch (ErrnoException e) {
            Log.e(TAG, "Unable to update entry for " + ifaceName + ", " + e);
        }
    }

    private class InterfaceChangeObserver extends BaseNetdUnsolicitedEventListener {
        @Override
        public void onInterfaceAdded(String ifName) {
            mHandler.post(() -> addInterface(ifName));
        }
    }

    /** get interface name by interface index from bpf map */
    public String getIfNameByIndex(final int index) {
        try {
            final InterfaceMapValue value = mIndexToIfaceBpfMap.getValue(new S32(index));
            if (value == null) {
                Log.e(TAG, "No if name entry for index " + index);
                return null;
            }
            return value.getInterfaceNameString();
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to get entry for index " + index + ": " + e);
            return null;
        }
    }

    /**
     * Dump BPF map
     *
     * @param pw print writer
     */
    public void dump(final IndentingPrintWriter pw) {
        pw.println("BPF map status:");
        pw.increaseIndent();
        BpfDump.dumpMapStatus(mIndexToIfaceBpfMap, pw, "IfaceIndexNameMap",
                IFACE_INDEX_NAME_MAP_PATH);
        pw.decreaseIndent();
        pw.println("BPF map content:");
        pw.increaseIndent();
        BpfDump.dumpMap(mIndexToIfaceBpfMap, pw, "IfaceIndexNameMap",
                (key, value) -> "ifaceIndex=" + key.val
                        + " ifaceName=" + value.getInterfaceNameString());
        pw.decreaseIndent();
    }
}
