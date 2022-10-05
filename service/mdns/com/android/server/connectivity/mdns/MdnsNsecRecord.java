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

package com.android.server.connectivity.mdns;

import android.net.DnsResolver;

import com.android.net.module.util.CollectionUtils;

import java.io.IOException;
import java.util.ArrayList;

public class MdnsNsecRecord extends MdnsRecord {
    private String[] mNextDomain;
    private int[] mTypes;

    public MdnsNsecRecord(String[] name, MdnsPacketReader reader) throws IOException {
        super(name, TYPE_NSEC, reader);
    }

    public MdnsNsecRecord(String[] name, long receiptTimeMillis, boolean cacheFlush, long ttlMillis,
            String[] nextDomain, int[] types) {
        super(name, TYPE_NSEC, DnsResolver.CLASS_IN, receiptTimeMillis, cacheFlush, ttlMillis);
        mNextDomain = nextDomain;
        mTypes = types;
    }

    @Override
    protected void readData(MdnsPacketReader reader) throws IOException {
        mNextDomain = reader.readLabels();
        mTypes = readTypes(reader);
    }

    // TODO: write tests with actual observed format
    private int[] readTypes(MdnsPacketReader reader) throws IOException {
        // See RFC3845 #2.1.2
        final ArrayList<Integer> types = new ArrayList<>();
        int prevBlockNumber = -1;
        while (reader.getRemaining() > 0) {
            final int blockNumber = reader.readUInt8();
            if (blockNumber <= prevBlockNumber) {
                throw new IOException(
                        "Unordered block number: " + blockNumber + " after " + prevBlockNumber);
            }
            prevBlockNumber = blockNumber;
            final int bitmapLength = reader.readUInt8();
            if (bitmapLength > 32) {
                throw new IOException("Invalid bitmap length: " + bitmapLength);
            }
            final byte[] bitmap = new byte[bitmapLength];
            reader.readBytes(bitmap);

            for (int bitmapIndex = 0; bitmapIndex < bitmap.length; bitmapIndex++) {
                final byte bitmapByte = bitmap[bitmapIndex];
                for (int bit = 0; bit < 8; bit++) {
                    if ((bitmapByte & (1 << bit)) != 0) {
                        types.add(blockNumber * 256 + bitmapIndex * 8 + bit);
                    }
                }
            }
        }

        return CollectionUtils.toIntArray(types);
    }

    @Override
    protected void writeData(MdnsPacketWriter writer) throws IOException {
        // No compression as per RFC3845 2.1.1
        writer.writeLabelsNoCompression(mNextDomain);

        int blockStart = 0;
        int prevBlockNumber = -1;
        int bytesInBlock = 0;
        for (int i = 0; i < mTypes.length; i++) {
            final int type = mTypes[i];
            final int blockNumber = type / 256;
            if (blockNumber != prevBlockNumber && prevBlockNumber >= 0) {
                writeTypeBlock(writer, blockStart, i - 1, bytesInBlock);

                blockStart = i;
            }
            prevBlockNumber = blockNumber;

            final int byteIndex = (type % 256) / 8;
            bytesInBlock = Math.max(bytesInBlock, byteIndex + 1);
        }

        if (bytesInBlock > 0) {
            writeTypeBlock(writer, blockStart, mTypes.length - 1, bytesInBlock);
        }
    }

    private void writeTypeBlock(MdnsPacketWriter writer,
            int startIndex, int endIndex, int bytesInBlock) throws IOException {
        final byte[] bytes = new byte[bytesInBlock];
        for (int i = startIndex; i <= endIndex; i++) {
            final int type = mTypes[i];
            bytes[(type % 256) / 8] |= 1 << (type % 8);
        }
        writer.writeBytes(bytes);
    }
}
