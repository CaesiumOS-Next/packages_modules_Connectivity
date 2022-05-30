/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * This class provides APIs to do a delayed data write to a given {@link OutputStream}.
 */
public class DelayedDiskWrite {
    private static final String TAG = "DelayedDiskWrite";

    private final HandlerThread mDiskWriteHandlerThread;
    private final Handler mDiskWriteHandler;

    @VisibleForTesting
    DelayedDiskWrite(Handler handler) {
        mDiskWriteHandler = handler;
        mDiskWriteHandlerThread = null;
    }

    public DelayedDiskWrite() {
        mDiskWriteHandlerThread = new HandlerThread("DelayedDiskWriteThread");
        mDiskWriteHandlerThread.start();
        mDiskWriteHandler = new Handler(mDiskWriteHandlerThread.getLooper());
    }

    /**
     * Used to do a delayed data write to a given {@link OutputStream}.
     */
    public interface Writer {
        /**
         * write data to a given {@link OutputStream}.
         */
        void onWriteCalled(DataOutputStream out) throws IOException;
    }

    /**
     * Do a delayed data write to a given output stream opened from filePath.
     */
    public void write(final String filePath, final Writer w) {
        write(filePath, w, true);
    }

    /**
     * Do a delayed data write to a given output stream opened from filePath.
     */
    public void write(final String filePath, final Writer w, final boolean open) {
        if (TextUtils.isEmpty(filePath)) {
            throw new IllegalArgumentException("empty file path");
        }
        mDiskWriteHandler.post(() -> doWrite(filePath, w, open));
    }

    private void doWrite(String filePath, Writer w, boolean open) {
        DataOutputStream out = null;
        try {
            if (open) {
                out = new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream(filePath)));
            }
            w.onWriteCalled(out);
        } catch (IOException e) {
            loge("Error writing data file " + filePath);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) { }
            }
        }
    }

    private void loge(String s) {
        Log.e(TAG, s);
    }
}
