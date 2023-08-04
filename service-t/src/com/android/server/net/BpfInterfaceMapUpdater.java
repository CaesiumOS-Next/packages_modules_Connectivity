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

import android.content.Context;
import android.net.INetd;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.BaseNetdUnsolicitedEventListener;
import com.android.net.module.util.BpfDump;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.Struct.S32;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Monitor interface added (without removed) and right interface name and its index to bpf map.
 */
public class BpfInterfaceMapUpdater {
    private static final String TAG = BpfInterfaceMapUpdater.class.getSimpleName();
    // This is current path but may be changed soon.
    private static final String IFACE_INDEX_NAME_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_iface_index_name_map";
    private final IBpfMap<S32, InterfaceMapValue> mIndexToIfaceBpfMap;
    private final HashMap<String, HashSet<Integer>> mIfaceNameToIndexCache = new HashMap<>();
    private final INetd mNetd;
    private final Handler mHandler;
    private final Dependencies mDeps;

    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();
    private final Lock mWriteLock = mReadWriteLock.writeLock();
    // The read lock is package-private to allow access from NetworkStatsService.
    final Lock mReadLock = mReadWriteLock.readLock();

    public BpfInterfaceMapUpdater(Context ctx, Handler handler) {
        this(ctx, handler, new Dependencies());
    }

    @VisibleForTesting
    public BpfInterfaceMapUpdater(Context ctx, Handler handler, Dependencies deps) {
        mDeps = deps;
        mIndexToIfaceBpfMap = deps.getInterfaceMap();
        mNetd = deps.getINetd(ctx);
        mHandler = handler;
        // Populates the name->index cache using existing BpfMap index->name entries. This handles
        // the case in which a system_server crash clears the cache.
        try {
            mIndexToIfaceBpfMap.forEach((index, name) -> {
                String ifaceName = name.getInterfaceNameString();
                mIfaceNameToIndexCache.putIfAbsent(ifaceName, new HashSet<Integer>());
                mIfaceNameToIndexCache.get(ifaceName).add(index.val);
            });
        } catch (ErrnoException e) {
            Log.wtf(TAG, "Cannot populate initial name->index cache: " + e);
        }
    }

    /**
     * Dependencies of BpfInerfaceMapUpdater, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /** Create BpfMap for updating interface and index mapping. */
        public IBpfMap<S32, InterfaceMapValue> getInterfaceMap() {
            try {
                return new BpfMap<>(IFACE_INDEX_NAME_MAP_PATH, BpfMap.BPF_F_RDWR,
                    S32.class, InterfaceMapValue.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create interface map: " + e);
                return null;
            }
        }

        /** Get InterfaceParams for giving interface name. */
        public InterfaceParams getInterfaceParams(String ifaceName) {
            return InterfaceParams.getByName(ifaceName);
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

    HashSet<Integer> getIfaceIndices(String ifaceName) {
        // Consumers of this index set should acquire 'readLock' while iterating through it.
        return mIfaceNameToIndexCache.get(ifaceName);
    }

    private synchronized void addInterface(String ifaceName) {
        final InterfaceParams iface = mDeps.getInterfaceParams(ifaceName);
        if (iface == null) {
            Log.e(TAG, "Unable to get InterfaceParams for " + ifaceName);
            return;
        }

        mWriteLock.lock();
        try {
            boolean newEntry = mIndexToIfaceBpfMap.insertOrReplaceEntry(new S32(iface.index),
                    new InterfaceMapValue(ifaceName));
            // If this index was mapped to an interface before, it needs to be removed from the
            // name-to-index cache to avoid double-counting traffic. This shouldn't happen, but is
            // implemented just in case.
            if (!newEntry) {
                for (HashSet<Integer> indices : mIfaceNameToIndexCache.values()) {
                    indices.remove(iface.index);
                }
            }
            // The set of indices needs to be instantiated if this is the first occurrence of this
            // interface name.
            mIfaceNameToIndexCache.putIfAbsent(ifaceName, new HashSet<Integer>());
            mIfaceNameToIndexCache.get(ifaceName).add(iface.index);
        } catch (ErrnoException e) {
            Log.e(TAG, "Unable to update entry for " + ifaceName + ", " + e);
        } finally {
            mWriteLock.unlock();
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
