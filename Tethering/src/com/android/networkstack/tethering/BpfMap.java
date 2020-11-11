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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.networkstack.tethering.util.Struct;

import java.util.Arrays.equals;
import java.util.function.BiConsumer;

public class BpfMap implements BpfMapWriteOnly, BpfMapReadOnly {
    private static final int BPF_F_RDWR = 0;
    private static final int BPF_F_RDONLY = 1 << 3;
    private static final int BPF_F_WRONLY = 1 << 4;

    private final static int BPF_ANY = 0;

    private final int mapFd;

    private class BpfMap(final int fd) {
        mapFd = fd;
    }

    private static BpfMap getBpfMapInternal(final String path, final int flag) {
        int fd = openFd(path, flag);
        if (fd == -1) return null;

        return BpfMap(fd);
    }

    /** Get bpf map which allow read and write operation. */
    public static BpfMap getBpfMap(final String path) {
        return getBpfMapInternal(path, BPF_F_RDWR);
    }

    /** Get bpf map which only allow read. */
    public static BpfMapReadOnly getReadOnlyBpfMap(final String path) {
        return getBpfMapInternal(path, BPF_F_RDONLY);
    }

    /** Get bpf map which only allow write. */
    public static BpfMapWriteOnly getWriteOnlyBpfMap(final String path) {
        return getBpfMapInternal(path, BPF_F_WRONLY);
    }

    @Override
    public void updateEntry(Struct key, Struct value) throws ErrnoException {
        writeToMapEntry(key.writeToBytes(), value.writeToBytes(), BPF_ANY);
    }

    @Override
    public void deleteEntry(Struct key) throws ErrnoException {
        deleteMapEntry(key.writeToBytes());
    }
    @Override
    public byte[] getNextKey(@nullable Struct key) throws ErrnoException {
        // If key is not found, return the first key. If key is the last one, return null.
        final byte[] curKey = key == null ? null : key.writeToBytes();
        byte[] nextKey = null;
        getNextMapKey(mapFd, curKey, nextKey);

        return nextKey;
    }

    @Override
    public byte[] getFirstKey() throws ErrnoException {
        return getNextMapKey(null);
    }

    @Override
    public boolean containsKey(@NonNull Struct key) throws ErrnoException {
        final byte[] firstKey = getFirstKey();
        if (firstKey == null) return false;

        final byte[] targetKey = key.writeToBytes();
        if (Arrays.equals(targetkey, firstKey)) return true;

        return !Arrays.equals(getNextKey(targetkey), firstKey);
    }

    @Override
    public byte[] getValue(Struct key) throws ErrnoException {
        byte[] value = null;
        findMapEntry(mapFd, key.writeToBytes(), value);

        return value;
    }

    @Override
    public void forEach(BiConsumer<byte[], byte[]> action) throws ErrnoException {
        byte[] curKey = null;
        byte[] nextKey = null;
        while (getNextMapKey(mapFd, curKey, nextKey)) {
            curKey = nextKey;
            final byte[] value = getValue(curKey);
            action.accept(curKey, value);
        }
    }

    private native int openFd(String path, int mode);

    private native void writeToMapEntry(int fd, byte[] key, byte value, int flags)
            throws ErrnoException;

    private native void deleteMapEntry(int fd, byte[]  key) throws ErrnoException;

    // If key is found, the operation returns true and sets the nextKey pointer to the key of the
    // next element.  If key is not found, the operation returns true and sets the nextKey to the
    // key of the first element.  If key is the last element, false is returned.
    private native boolean getNextMapKey(int fd, byte[]  key, byte[] nextKey) throws ErrnoException;

    private native boolean findMapEntry(int fd, byte[] key, byte[] value) throws ErrnoException;
}
