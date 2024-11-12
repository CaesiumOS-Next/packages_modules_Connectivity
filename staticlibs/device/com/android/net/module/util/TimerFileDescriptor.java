/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.os.ParcelFileDescriptor;
import android.util.CloseGuard;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a Timer file descriptor object used for scheduling tasks with precise delays.
 * Compared to {@link Handler#postDelayed}, this class offers enhanced accuracy for delayed
 * callbacks by accounting for periods when the device is in deep sleep.
 *
 *  <p> This class is designed for use exclusively from the handler thread.
 *
 * **Usage Examples:**
 *
 * **Example 1: Scheduling recurring tasks with the same TimerFileDescriptor**
 *
 * ```java
 * // Create a TimerFileDescriptor
 * final TimerFileDescriptor timerFd = new TimerFileDescriptor(handler);
 *
 * // Schedule a new task with a delay.
 * timerFd.setDelayedTask(() -> taskToExecute(), delayTime);
 *
 * // Once the delay has elapsed, and the task is running, schedule another task.
 * timerFd.setDelayedTask(() -> anotherTaskToExecute(), anotherDelayTime);
 *
 * // Remember to close the TimerFileDescriptor when done.
 * timerFd.close();
 * ```
 *
 * **Example 2: Scheduling a single task using a try-with-resources statement**
 *
 * ```java
 * // Create a TimerFileDescriptor within a try-with-resources block
 * try (TimerFileDescriptor timerFd = new TimerFileDescriptor(handler)) {
 *     timerFd.setDelayedTask(() -> taskToExecute(), delayTime);
 * }
 * ```
 */
public class TimerFileDescriptor implements AutoCloseable {
    private static final String TAG = TimerFileDescriptor.class.getSimpleName();
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    private final CloseGuard mGuard = new CloseGuard();
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final MessageQueue mQueue;
    @NonNull
    private final ParcelFileDescriptor mParcelFileDescriptor;
    private final int mId;
    @NonNull
    private final FileDescriptor mFileDescriptor;
    @Nullable
    private Runnable mTask;

    public TimerFileDescriptor(@NonNull Looper looper) {
        mId = TimerFdUtils.createTimerFileDescriptor();
        mParcelFileDescriptor = ParcelFileDescriptor.adoptFd(mId);
        mFileDescriptor = Objects.requireNonNull(mParcelFileDescriptor.getFileDescriptor());
        mHandler = new Handler(looper);
        mQueue = looper.getQueue();
        registerFdEventListener();

        mGuard.open("close");
    }

    private void checkAndRunOnHandlerThread(@NonNull Runnable function) {
        if (isRunningOnHandlerThread()) {
            function.run();
        } else {
            mHandler.post(function);
        }
    }

    /**
     * Set a task to be executed after a specified delay.
     *
     * <p> A task can only be scheduled once at a time. Cancel previous scheduled task before the
     *     new task is scheduled.
     *
     * @param task the task to be executed
     * @param delayMs the delay time in milliseconds
     * @throws IllegalArgumentException if try to replace the current scheduled task
     * @throws IllegalArgumentException if the delay time is less than 0
     */
    public void setDelayedTask(@NonNull Runnable task, long delayMs) {
        checkAndRunOnHandlerThread(() -> scheduleExpirationTimeForTask(task, delayMs));
    }

    private void scheduleExpirationTimeForTask(@NonNull Runnable task, long delayMs) {
        if (mTask != null) {
            throw new IllegalArgumentException("task is already scheduled");
        }
        if (delayMs <= 0L) {
            task.run();
            mTask = null;
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
        checkAndRunOnHandlerThread(() -> {
            TimerFdUtils.setExpirationTime(mId, 0 /* delayMs */);
            mTask = null;
        });
    }

    /**
     * Check if there is a scheduled task.
     */
    public boolean hasDelayedTask() {
        if (isRunningOnHandlerThread()) {
            return mTask != null;
        } else {
            final CompletableFuture<Boolean> future = new CompletableFuture<>();
            mHandler.post(() -> future.complete(mTask != null));
            try {
                return future.get();
            } catch (Exception e) {
                Log.e(TAG, "Get delayed task failed. e=" + e);
                return false;
            }
        }
    }

    /**
     * Close the TimerFileDescriptor. This implementation closes the underlying
     * OS resources allocated to represent this stream.
     */
    @Override
    public void close() {
        checkAndRunOnHandlerThread(() -> unregisterAndDestroyFd());
    }

    private void registerFdEventListener() {
        mQueue.addOnFileDescriptorEventListener(
                mFileDescriptor,
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
        return mFileDescriptor.valid();
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
        if (mGuard != null) {
            mGuard.close();
        }

        mQueue.removeOnFileDescriptorEventListener(mFileDescriptor);
        mParcelFileDescriptor.detachFd();
        closeFd(mFileDescriptor);
    }

    private static void closeFd(FileDescriptor fd) {
        try {
            SocketUtils.closeSocket(fd);
        } catch (IOException exception) {
            Log.e("TAG", "closeSocket failed. ", exception);
        }
    }

    private boolean isRunningOnHandlerThread() {
        if (mHandler.getLooper().getThread() == Thread.currentThread()) {
            return true;
        }
        return false;
    }

    @SuppressWarnings("Finalize")
    @Override
    protected void finalize() throws Throwable {
        if (mGuard != null) {
            mGuard.warnIfOpen();
        }
        super.finalize();
    }
}
