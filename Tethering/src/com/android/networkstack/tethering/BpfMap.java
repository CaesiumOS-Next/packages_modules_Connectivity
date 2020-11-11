/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.networkstack.tethering;

import static android.system.OsConstants.EEXIST;
import static android.system.OsConstants.ENOENT;

import android.net.util.Struct;
import android.system.ErrnoException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * BpfMap is a key -> value mapping structure that is designed to maintained the bpf map entries.
 * This is a wrapper class of in-kernerl data structure. The in-kernel data can be read/write by
 * passing syscalls with map file descriptor.
 *
 * @param <K> the key of the map.
 * @param <V> the value of the map.
 */
public class BpfMap<K extends Struct, V extends Struct> implements AutoCloseable {
    // Following definitions from kernel include/uapi/linux/bpf.h
    public static final int BPF_F_RDWR = 0;
    public static final int BPF_F_RDONLY = 1 << 3;
    public static final int BPF_F_WRONLY = 1 << 4;

    public static final int BPF_MAP_TYPE_HASH = 1;

    private static final int BPF_F_NO_PREALLOC = 1;

    private static final int BPF_ANY = 0;
    private static final int BPF_NOEXIST = 1;
    private static final int BPF_EXIST = 2;

    private final int mMapFd;
    private final Class<K> mKeyClass;
    private final Class<V> mValueClass;
    private final int mKeySize;
    private final int mValueSize;

    /**
     * Create a BpfMap map wrapper with "path" of filesystem. Passing "flag" (BPF_F_RDWR,
     * BPF_F_RDONLY, or BPF_F_WRONLY) to specify file mode configuration.
     */
    public BpfMap(final String path, final int flag, final Class<K> key, final Class<V> value)
            throws ErrnoException {
        mMapFd = bpfFdGet(path, flag);

        mKeyClass = key;
        mValueClass = value;
        mKeySize = Struct.getSize(key);
        mValueSize = Struct.getSize(value);
    }

    /**
     * Update a new key -> value entry to eBpf map. If the key already exists in the map, its value
     * would be replaced.
     */
    public void updateEntry(Struct key, Struct value) throws ErrnoException {
        writeToMapEntry(mMapFd, key.writeToBytes(), value.writeToBytes(), BPF_ANY);
    }

    /**
     * If the key do not exists in the map, insert key -> value entry to eBpf map.
     * Otherwise, return false.
     */
    public boolean insertEntry(Struct key, Struct value) throws ErrnoException {
        try {
            writeToMapEntry(mMapFd, key.writeToBytes(), value.writeToBytes(), BPF_NOEXIST);
        } catch (ErrnoException e) {
            if (e.errno == EEXIST) return false;

            throw new ErrnoException("insertEntry", e.errno);
        }

        return true;
    }

    /**
     * If the key already exist in the map, repalce its corresponding value. Otherwise, return
     * false.
     */
    public boolean replaceEntry(Struct key, Struct value) throws ErrnoException {
        try {
            writeToMapEntry(mMapFd, key.writeToBytes(), value.writeToBytes(), BPF_EXIST);
        } catch (ErrnoException e) {
            if (e.errno == ENOENT) return false;

            throw new ErrnoException("replaceEntry", e.errno);
        }

        return true;
    }

    /** Remove existing key from eBpf map. Return false if map do not be modified. */
    public boolean deleteEntry(Struct key) throws ErrnoException {
        return deleteMapEntry(mMapFd, key.writeToBytes());
    }

    /**
     * Get the next key of passing key. If passing key is not found, return the first key. If
     * passing key is the last one, return null.
     */
    public K getNextKey(@Nullable Struct key) throws ErrnoException {
        final byte[] rawKey = getNextRawKey(key == null ? new byte[mKeySize] : key.writeToBytes());

        if (rawKey == null) return null;

        final ByteBuffer buffer = ByteBuffer.wrap(rawKey);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return Struct.parse(mKeyClass, buffer);
    }

    private byte[] getNextRawKey(final byte[] key) throws ErrnoException {
        byte[] nextKey = new byte[mKeySize];
        if (getNextMapKey(mMapFd, key, nextKey)) return nextKey;

        return null;
    }

    /** Get the first key of eBpf map. */
    public K getFirstKey() throws ErrnoException {
        return getNextKey(null);
    }

    /** Check whether a key exists in the map. */
    public boolean containsKey(@NonNull Struct key) throws ErrnoException {
        final byte[] firstKey = getNextRawKey(new byte[mKeySize]);
        if (firstKey == null) return false;

        final byte[] targetKey = key.writeToBytes();
        if (Arrays.equals(targetKey, firstKey)) return true;

        return !Arrays.equals(getNextRawKey(targetKey), firstKey);
    }

    /** Retrieve a value from the map. Return null if there is no such key. */
    public V getValue(@NonNull Struct key) throws ErrnoException {
        final byte[] rawValue = getRawValue(key.writeToBytes());

        if (rawValue == null) return null;

        final ByteBuffer buffer = ByteBuffer.wrap(rawValue);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return Struct.parse(mValueClass, buffer);
    }

    private byte[] getRawValue(final byte[] key) throws ErrnoException {
        byte[] value = new byte[mValueSize];
        if (findMapEntry(mMapFd, key, value)) return value;

        return null;
    }

    /**
     * Iterate through the map and handle each key -> value retrieved base on the given BiConsumer.
     */
    public void forEach(BiConsumer<byte[], byte[]> action) throws ErrnoException {
        byte[] curKey = new byte[mKeySize];
        byte[] nextKey = new byte[mKeySize];
        while (getNextMapKey(mMapFd, curKey, nextKey)) {
            curKey = nextKey;
            final byte[] value = getRawValue(curKey);
            action.accept(curKey, value);
        }
    }

    @Override
    public void close() throws Exception {
        closeMap(mMapFd);
    }

    private static native void closeMap(int fd) throws ErrnoException;

    private native int bpfFdGet(String path, int mode) throws ErrnoException;

    private native void writeToMapEntry(int fd, byte[] key, byte[] value, int flags)
            throws ErrnoException;

    private native boolean deleteMapEntry(int fd, byte[]  key) throws ErrnoException;

    // If key is found, the operation returns true and the nextKey would reference to the next
    // element.  If key is not found, the operation returns true and the nextKey would reference to
    // the first element.  If key is the last element, false is returned.
    private native boolean getNextMapKey(int fd, byte[]  key, byte[] nextKey) throws ErrnoException;

    private native boolean findMapEntry(int fd, byte[] key, byte[] value) throws ErrnoException;
}
