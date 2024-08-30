package com.android.server.thread;

import com.android.net.module.util.SharedLog;

/**
 * A SharedLog for Thread network.
 *
 * <p>Each class should log with its own tag using the logger of
 * ThreadNetworkLogger.getInstance().forSubComponent(TAG).
 */
public final class ThreadNetworkLogger {
    private static final String TAG = "ThreadNetwork";
    private static final SharedLog mLog = new SharedLog(TAG);

    public static SharedLog getInstance() {
        return mLog;
    }
}
