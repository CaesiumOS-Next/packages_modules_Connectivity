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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.INetd;
import android.net.IRoutingCoordinator;
import android.net.RouteInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;

import static com.android.net.module.util.NetdUtils.toRouteInfoParcel;

import com.android.internal.annotations.GuardedBy;
import com.android.net.module.util.CollectionUtils;

import java.util.Objects;

public class RoutingCoordinatorService extends IRoutingCoordinator.Stub {
    private final Context mContext;
    private final INetd mNetd;

    // If netd is null, will fetch it from the context. The argument is here for
    // dependency injection.
    public RoutingCoordinatorService(Context context, @Nullable INetd netd) {
        mContext = context;
        mNetd = null != netd ? netd
                : INetd.Stub.asInterface((IBinder) context.getSystemService(Context.NETD_SERVICE));
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

    @Override
    public void updateRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkUpdateRouteParcel(netId, toRouteInfoParcel(route));
    }

    public void addInterfaceToNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkAddInterface(netId, iface);
    }

    public void removeInterfaceFromNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkRemoveInterface(netId, iface);
    }

    private final Object mIfacesLock = new Object();
    private class ForwardingPair {
        @NonNull public final String fromIface;
        @NonNull public final String toIface;
        public boolean enableNat;
        ForwardingPair(@NonNull final String fromIface, @NonNull final String toIface,
                final boolean enableNat) {
            this.fromIface = fromIface;
            this.toIface = toIface;
            this.enableNat = enableNat;
        }

        // Note that equals() and hashcode() only use fromIface and toIface, not enableNat.
        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final ForwardingPair that = (ForwardingPair) o;

            if (!fromIface.equals(that.fromIface)) return false;
            return toIface.equals(that.toIface);
        }

        @Override
        public int hashCode() {
            int result = fromIface.hashCode();
            result = 2 * result + toIface.hashCode();
            return result;
        }
    }

    @GuardedBy("mIfacesLock")
    private final ArraySet<ForwardingPair> forwardedInterfaces = new ArraySet<>();
    public void addInterfaceForward(final String fromIface, final String toIface,
            final boolean enableNat) throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        synchronized (mIfacesLock) {
            if (forwardedInterfaces.size() == 0) {
                mNetd.ipfwdEnableForwarding("RoutingCoordinator");
            }
            final ForwardingPair fwp = new ForwardingPair(fromIface, toIface, enableNat);
            // enableNat is ignored by FowardingPair#equals and #hashcode.
            if (forwardedInterfaces.contains(fwp)) {
                throw new IllegalStateException("Forward already exists between ifaces "
                        + fromIface + " → " + toIface);
            }
            forwardedInterfaces.add(fwp);
            if (enableNat) mNetd.tetherAddForward(fromIface, toIface);
            mNetd.ipfwdAddInterfaceForward(fromIface, toIface);
        }
    }

    public void removeInterfaceForward(final String fromIface, final String toIface)
            throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        synchronized (mIfacesLock) {
            final ForwardingPair fwp = CollectionUtils.findFirst(forwardedInterfaces,
                    p -> fromIface.equals(p.fromIface) && toIface.equals(p.toIface));
            if (null == fwp) {
                throw new IllegalStateException("No forward set up between interfaces "
                        + fromIface + " → " + toIface);
            }
            forwardedInterfaces.remove(fwp);
            mNetd.ipfwdRemoveInterfaceForward(fromIface, toIface);
            if (fwp.enableNat) mNetd.tetherRemoveForward(fromIface, toIface);
            if (forwardedInterfaces.size() == 0) {
                mNetd.ipfwdDisableForwarding("RoutingCoordinator");
            }
        }
    }
}
