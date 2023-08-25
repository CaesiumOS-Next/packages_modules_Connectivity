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

package com.android.server.connectivity;

import android.content.Context;
import android.net.INetd;
import android.net.IRoutingCoordinator;
import android.net.RouteInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import static com.android.net.module.util.NetdUtils.toRouteInfoParcel;

public class RoutingCoordinatorService extends IRoutingCoordinator.Stub {
    private final Context mContext;
    private final INetd mNetd;

    public RoutingCoordinatorService(Context context) {
        mContext = context;
        mNetd = INetd.Stub.asInterface((IBinder) context.getSystemService(Context.NETD_SERVICE));
    }

    @Override
    public void addRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkAddRouteParcel(netId, toRouteInfoParcel(route));
    }

    @Override
    public void removeRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkRemoveRouteParcel(netId, toRouteInfoParcel(route));
    }

    public void addInterfaceToNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkAddInterface(netId, iface);
    }

    public void removeInterfaceFromNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkRemoveInterface(netId, iface);
    }
}
