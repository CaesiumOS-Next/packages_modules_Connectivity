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

package com.android.net.module.util;

import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;

import android.net.util.SocketUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Timer file descriptor object
 *
 * <p>This class is intended to be used only from the handler thread.
 */
public class TimerFileDescriptor implements AutoCloseable {
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final MessageQueue mQueue;
    final int mId;
    @Nullable
    private FileDescriptor mFd;
    @Nullable
    private Runnable mTask;

    public TimerFileDescriptor(@NonNull Handler handler) {
        mId = TimerFdUtils.createTimerFileDescriptor();
        mFd = new FileDescriptor();
        mFd.setInt$(mId);
        IoUtils.setFdOwner(mFd, this);

        mHandler = handler;
        mQueue = handler.getLooper().getQueue();
        createAndRegisterFd();
    }

    /**
     * Set a task to be executed after a specified delay.
     *
     * @param task the task to be executed
     * @param delayMs the delay time in milliseconds
     */
    public void setDelayedTask(@NonNull Runnable task, long delayMs) {
        ensureRunningOnCorrectThread();
        if (delayMs < 0) {
            throw new IllegalArgumentException("Delay cannot be set to a value less than 0");
        }

        if (delayMs == 0L) {
            task.run();
            return;
        }

        if (TimerFdUtils.setExpirationTime(mId, delayMs)) {
            mTask = task;
        }
    }

    /**
     * Cancel the scheduled task.
     */
    public void cancelTask() {
        ensureRunningOnCorrectThread();
        TimerFdUtils.setExpirationTime(mId, 0 /* delayMs */);
        mTask = null;
    }

    /**
     * Check if there is a scheduled task.
     */
    public boolean hasDelayedTask() {
        ensureRunningOnCorrectThread();
        return mTask != null;
    }

    /**
     * Return the remaining time until the scheduled task's expiration, in milliseconds.
     */
    public long getRemainingExpirationTime() {
        ensureRunningOnCorrectThread();
        return TimerFdUtils.getExpirationTime(mId);
    }

    @Override
    public void close() {
        ensureRunningOnCorrectThread();
        unregisterAndDestroyFd();
    }

    private void createAndRegisterFd() {
        if (mFd == null) return;

        mQueue.addOnFileDescriptorEventListener(
                mFd,
                FD_EVENTS,
                (fd, events) -> {
                    if (!isRunning() || !handleExpiration()) {
                        unregisterAndDestroyFd();
                        return 0;
                    }
                    return FD_EVENTS;
                });
    }

    private boolean isRunning() {
        return (mFd != null) && mFd.valid();
    }

    private boolean handleExpiration() {
        // Disarm the timer
        TimerFdUtils.setExpirationTime(mId, 0);
        // Execute the task
        if (mTask != null) {
            mTask.run();
            mTask = null;
        }
        return true;
    }

    private void unregisterAndDestroyFd() {
        if (mFd == null) return;

        mQueue.removeOnFileDescriptorEventListener(mFd);
        closeFd(mFd);
        mFd = null;
    }

    private static void closeFd(FileDescriptor fd) {
        try {
            SocketUtils.closeSocket(fd);
        } catch (IOException ignored) {
        }
    }

    private void ensureRunningOnCorrectThread() {
        if (mHandler.getLooper() != Looper.myLooper()) {
            throw new IllegalStateException(
                    "Not running on Handler thread: " + Thread.currentThread().getName());
        }
    }
}
