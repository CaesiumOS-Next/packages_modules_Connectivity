/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.networkstack.tethering.wear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.networkstack.tethering.wear.WearableConnection.WriteDataBuffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class TunDeviceTest {
    private static final long DELAY_FOR_ASSERTS_MS = 200;
    public static final byte[] TEST_DATA = {1, 2, 3, 4};
    public static final byte[] TEST_LARGE_DATA = new byte[1024];
    private static final int TEST_DATA_BUFFER_SIZE = 2048;
    private static final byte[] MAX_INPUT_DATA_PACKET = new byte[2050];

    private TunDevice mTun;

    protected Handler mHandler;
    private final List<Closeable> mCloseableList = new ArrayList<>();
    private ParcelFileDescriptor mFakeTunSocketFd;
    private final WriteDataBuffer mTestDataReceiver = new WriteDataBuffer(TEST_DATA_BUFFER_SIZE);

    static {
        for (int i = 0; i < TEST_LARGE_DATA.length; i++) {
            TEST_LARGE_DATA[i] = (byte) (i & 0xFF);
        }
        Arrays.fill(MAX_INPUT_DATA_PACKET, (byte) 0x5a);
    }

    @Before
    public void setUp() throws Exception {
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        mFakeTunSocketFd = pair[0];
        Collections.addAll(mCloseableList, pair);
        mHandler = new Handler(Looper.getMainLooper());
        mTun = new TunDevice(mHandler, "fakeTap0", pair[1]);
    }

    @After
    public void tearDown() throws InterruptedException {
        doOnMain(() -> mTun.stop());
        for (Closeable c : mCloseableList) {
            try {
                c.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    @Test
    public void transfer() throws IOException, InterruptedException {
        doOnMain(
                () -> {
                    mTun.start(mTestDataReceiver);
                    mTun.sendNetworkPacket(ByteBuffer.wrap(TEST_DATA));
                });

        try (InputStream is = new FileInputStream(mFakeTunSocketFd.getFileDescriptor())) {
            assertTrue(is.available() > 0);
            byte[] buffer = new byte[1024];
            int res = is.read(buffer);
            assertArrayEquals(TEST_DATA, Arrays.copyOf(buffer, res));
        }
    }

    @Test
    public void startReadData()
            throws InterruptedIOException, ErrnoException, InterruptedException {
        doOnMain(
                () -> {
                    mTun.start(mTestDataReceiver);
                });

        Os.write(mFakeTunSocketFd.getFileDescriptor(), TEST_DATA, 0, TEST_DATA.length);

        doOnMain(
                () -> {
                    assertArrayEquals(TEST_DATA, testDrainBuffer());
                });
    }

    private byte[] testDrainBufferS() {
        byte[] data = new byte[mTestDataReceiver.remaining()];
        mTestDataReceiver.get(0, data, 0, data.length);
        mTestDataReceiver.advance(data.length);
        return data;
    }

    private byte[] testDrainBuffer() {
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            return testDrainBufferS();
        }
        try {
            return callOnMain(this::testDrainBufferS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void startReadDataAlreadyAvailable()
            throws InterruptedIOException, ErrnoException, InterruptedException {
        Os.write(mFakeTunSocketFd.getFileDescriptor(), TEST_DATA, 0, TEST_DATA.length);

        mHandler.post(
                () -> {
                    mTun.start(mTestDataReceiver);
                });

        doOnMain(() -> {});
        assertArrayEquals(TEST_DATA, testDrainBuffer());
    }

    @Test
    public void inputBufferStall()
            throws InterruptedIOException, ErrnoException, InterruptedException {
        Os.write(
                mFakeTunSocketFd.getFileDescriptor(),
                MAX_INPUT_DATA_PACKET,
                0,
                TEST_DATA_BUFFER_SIZE);
        Os.write(mFakeTunSocketFd.getFileDescriptor(), TEST_DATA, 0, TEST_DATA.length);
        mHandler.post(
                () -> {
                    mTun.start(mTestDataReceiver);
                });
        doOnMain(
                () -> {
                    // buffer filled up to capacity and polling stopped.
                    assertEquals(TEST_DATA_BUFFER_SIZE, mTestDataReceiver.remaining());
                });

        // drain buffer and kick tun to resume polling and reading
        mHandler.post(this::testDrainBuffer);
        doOnMain(
                () -> {
                    assertArrayEquals(TEST_DATA, testDrainBuffer());
                });
    }

    private <T> T callOnMain(Callable<T> callable) throws InterruptedException {
        AtomicReference<Throwable> captureException = new AtomicReference<>();
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(
                () -> {
                    try {
                        result.set(callable.call());
                    } catch (Throwable e) {
                        captureException.set(e);
                    }
                    latch.countDown();
                });
        latch.await();
        Throwable e = captureException.get();
        if (e != null) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new AssertionError("callSync: unexpected exception.", e);
        }
        return result.get();
    }

    private interface BlockRunnable {
        void run() throws Exception;
    }

    private void doOnMain(BlockRunnable r) throws InterruptedException {
        AtomicReference<Throwable> captureException = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(
                () -> {
                    try {
                        r.run();
                    } catch (Throwable e) {
                        captureException.set(e);
                    }
                    latch.countDown();
                });
        latch.await();
        Throwable e = captureException.get();
        if (e != null) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new AssertionError("doBlock: unexpected exception.", e);
        }
    }
}
