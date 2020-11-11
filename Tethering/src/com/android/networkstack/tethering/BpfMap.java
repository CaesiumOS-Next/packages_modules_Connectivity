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

import android.net.util.Struct;
import android.system.ErrnoException;

import com.android.internal.annotations.VisibleForTesting;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.BiConsumer;

public class BpfMap<K, V> implements AutoCloseable {
    // Following definitions from kernel include/uapi/linux/bpf.h
    public static final int BPF_F_RDWR = 0;
    public static final int BPF_F_RDONLY = 1 << 3;
    public static final int BPF_F_WRONLY = 1 << 4;

    public static final int BPF_MAP_TYPE_HASH = 1;

    private static final int BPF_F_NO_PREALLOC = 1;

    private static final int BPF_ANY = 0;

    private final int mMapFd;
    private final int mKeySize;
    private final int mValueSize;

    public BpfMap(final int mapType, final int keySize, final int valueSize, final int maxEntries)
            throws IllegalStateException {
        mMapFd = createMap(mapType, keySize, valueSize, maxEntries, BPF_F_NO_PREALLOC);
        if (mMapFd == -1) throw new IllegalStateException("fail to open map fd");

        mKeySize = keySize;
        mValueSize = valueSize;
    }

    public BpfMap(final String path, final int flag, final int keySize, final int valueSize)
            throws IllegalStateException {
        mMapFd = openFd(path, BPF_ANY);
        if (mMapFd == -1) throw new IllegalStateException("fail to open map fd");

        mKeySize = keySize;
        mValueSize = valueSize;
    }

    public void updateEntry(Struct key, Struct value) throws ErrnoException {
        writeToMapEntry(mMapFd, key.writeToBytes(), value.writeToBytes(), BPF_ANY);
    }

    public boolean deleteEntry(Struct key) throws ErrnoException {
        return deleteMapEntry(mMapFd, key.writeToBytes());
    }

    public K getNextKey(@Nullable Struct key, Class<K> clazz) throws ErrnoException {
        final byte[] rawKey = getNextRawKey(key == null ? new byte[mKeySize] : key.writeToBytes());

        if (rawKey == null) return null;

        final ByteBuffer buffer = ByteBuffer.wrap(rawKey);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return Struct.parse(clazz, buffer);
    }

    private byte[] getNextRawKey(final byte[] key) throws ErrnoException {
        // If key is not found, return the first key. If key is the last one, return null.
        byte[] nextKey = new byte[mKeySize];
        if (getNextMapKey(mMapFd, key, nextKey)) return nextKey;

        return null;
    }

    public K getFirstKey(Class<K> clazz) throws ErrnoException {
        return getNextKey(null, clazz);
    }

    public boolean containsKey(@NonNull Struct key) throws ErrnoException {
        final byte[] firstKey = getNextRawKey(new byte[mKeySize]);
        if (firstKey == null) return false;

        final byte[] targetKey = key.writeToBytes();
        if (Arrays.equals(targetKey, firstKey)) return true;

        return !Arrays.equals(getNextRawKey(targetKey), firstKey);
    }

    public V getValue(@NonNull Struct key, Class<V> clazz) throws ErrnoException {
        final byte[] rawValue = getRawValue(key.writeToBytes());

        if (rawValue == null) return null;

        final ByteBuffer buffer = ByteBuffer.wrap(rawValue);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return Struct.parse(clazz, buffer);
    }

    private byte[] getRawValue(final byte[] key) throws ErrnoException {
        byte[] value = new byte[mValueSize];
        if (findMapEntry(mMapFd, key, value)) return value;

        return null;
    }

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

    private native int createMap(int mapType, int keySize, int valueSize, int maxEntries, int flags);

    private native void closeMap(int fd) throws ErrnoException;

    private native int openFd(String path, int mode);

    private native void writeToMapEntry(int fd, byte[] key, byte[] value, int flags)
            throws ErrnoException;

    private native boolean deleteMapEntry(int fd, byte[]  key) throws ErrnoException;

    // If key is found, the operation returns true and the nextKey would reference to the next
    // element.  If key is not found, the operation returns true and the nextKey would reference to
    // the first element.  If key is the last element, false is returned.
    private native boolean getNextMapKey(int fd, byte[]  key, byte[] nextKey) throws ErrnoException;

    private native boolean findMapEntry(int fd, byte[] key, byte[] value) throws ErrnoException;
}
