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

package com.android.server.thread;

import static android.net.NetworkCapabilities.TRANSPORT_THREAD;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.MatchAllNetworkSpecifier;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.net.connectivity.com.android.modules.utils.HandlerExecutor;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkManager;
import android.net.thread.ThreadNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.SharedLog;

import java.util.concurrent.Executor;

/**
 * Serves network requests that are initiated from apps via the {@link
 * ConnectivityManager#requestNetwork} API.
 */
public final class ThreadNetworkFactory extends NetworkFactory {
    private static final SharedLog LOG =
            ThreadNetworkLogger.forSubComponent("ThreadNetworkFactory");

    private final ThreadNetworkController mController;
    private final Executor mExecutor;

    /** Creates a new {@link ThreadNetworkFactory} instance. */
    public static ThreadNetworkFactory newInstance(Context context, Looper looper) {
        ThreadNetworkController controller =
                context.getSystemService(ThreadNetworkManager.class)
                        .getAllThreadNetworkControllers()
                        .get(0);
        return new ThreadNetworkFactory(context, looper, controller);
    }

    @VisibleForTesting
    ThreadNetworkFactory(Context context, Looper looper, ThreadNetworkController controller) {
        super(looper, context, LOG.getTag(), createBaseCapabilities());
        mController = controller;
        mExecutor = new HandlerExecutor(new Handler(looper));
    }

    /** Returns the basic capabilities that all Thread networks initially have. */
    private static NetworkCapabilities createBaseCapabilities() {
        return new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_THREAD)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .setLinkUpstreamBandwidthKbps(100)
                .setLinkDownstreamBandwidthKbps(100)
                // By default accepts all network requests with a specifier
                .setNetworkSpecifier(new MatchAllNetworkSpecifier())
                .build();
    }

    @Override
    public boolean acceptRequest(@NonNull final NetworkRequest request) {
        // the filtering is done in {@link #needNetworkFor}
        return true;
    }

    @Override
    public void needNetworkFor(@NonNull final NetworkRequest request) {
        LOG.i("network request is received for " + request);
        if (request.getNetworkOrStubNetworkSpecifier()
                instanceof ThreadNetworkSpecifier specifier) {
            join(specifier);
        } else if (request.getNetworkOrStubNetworkSpecifier() != null) {
            LOG.w("Unrecognized network specifier: " + request.getNetworkOrStubNetworkSpecifier());
        } else if (request.getNetworkSpecifier() instanceof ThreadNetworkSpecifier specifier) {
            join(specifier);
        } else if (request.getNetworkSpecifier() != null
                || !request.hasTransport(TRANSPORT_THREAD)) {
            // Can do nothing if the client isn't requesting for a Thread network
            return;
        } else {
            join(null /* specifier */);
        }
    }

    private void join(@Nullable ThreadNetworkSpecifier specifier) {
        mController.join(
                specifier,
                mExecutor,
                new OutcomeReceiver<Void, ThreadNetworkException>() {
                    @Override
                    public void onError(ThreadNetworkException e) {
                        LOG.e("Failed to join Thread network: " + specifier, e);
                    }

                    @Override
                    public void onResult(Void v) {
                        LOG.i("Successfully joined Thread network: " + specifier);
                    }
                });
    }

    @Override
    public void releaseNetworkFor(@NonNull final NetworkRequest request) {
        LOG.i("network release request is received for " + request);
    }
}
