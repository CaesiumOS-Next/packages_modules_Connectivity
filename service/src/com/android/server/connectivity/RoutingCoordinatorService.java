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

import static com.android.net.module.util.NetdUtils.toRouteInfoParcel;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.INetd;
import android.net.IRoutingCoordinator;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.ip.RouterAdvertisementDaemon;
import android.net.ip.RouterAdvertisementDaemon.RaParams;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.InterfaceParams;

import java.util.Objects;
import java.util.Set;

public class RoutingCoordinatorService extends IRoutingCoordinator.Stub {
    private static final ArraySet<IpPrefix> sEmptySet = new ArraySet<>(0);

    private final Context mContext;
    private final INetd mNetd;

    // If netd is null, will fetch it from the context. The argument is here for
    // dependency injection.
    public RoutingCoordinatorService(Context context, @Nullable INetd netd) {
        mContext = context;
        mNetd = null != netd ? netd
                : INetd.Stub.asInterface((IBinder) context.getSystemService(Context.NETD_SERVICE));
    }

    /**
     * Add a route for specific network
     *
     * @param netId the network to add the route to
     * @param route the route to add
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void addRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkAddRouteParcel(netId, toRouteInfoParcel(route));
    }

    /**
     * Remove a route for specific network
     *
     * @param netId the network to remove the route from
     * @param route the route to remove
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void removeRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkRemoveRouteParcel(netId, toRouteInfoParcel(route));
    }

    /**
     * Update a route for specific network
     *
     * @param netId the network to update the route for
     * @param route parcelable with route information
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void updateRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkUpdateRouteParcel(netId, toRouteInfoParcel(route));
    }

    /**
     * Adds an interface to a network. The interface must not be assigned to any network, including
     * the specified network.
     *
     * @param netId the network to add the interface to.
     * @param iface the name of the interface to add.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
    @Override
    public void addInterfaceToNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkAddInterface(netId, iface);
    }

    /**
     * Removes an interface from a network. The interface must be assigned to the specified network.
     *
     * @param netId the network to remove the interface from.
     * @param iface the name of the interface to remove.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
    @Override
    public void removeInterfaceFromNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkRemoveInterface(netId, iface);
    }

    private final Object mIfacesLock = new Object();
    private class ForwardingPair {
        @NonNull public final String fromIface;
        @NonNull public final String toIface;
        public boolean enableNat;
        @Nullable public final RouterAdvertisementDaemon raDaemon;
        ForwardingPair(@NonNull final String fromIface, @NonNull final String toIface,
                final boolean enableNat, @Nullable final RouterAdvertisementDaemon raDaemon) {
            this.fromIface = fromIface;
            this.toIface = toIface;
            this.enableNat = enableNat;
            this.raDaemon = raDaemon;
        }

        // Note that equals() and hashcode() only use fromIface and toIface.
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
    private final ArraySet<ForwardingPair> mForwardedInterfaces = new ArraySet<>();

    /**
     * Add forwarding ip rule
     *
     * @param fromIface interface name to add forwarding ip rule
     * @param toIface interface name to add forwarding ip rule
     * @param enableNat whether to enable NAT between these two interfaces
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void addInterfaceForward(final String fromIface, final String toIface,
            final boolean enableNat) throws ServiceSpecificException, RemoteException {
        addInterfaceForward(fromIface, toIface, 0 /* mtu */, sEmptySet, enableNat);
    }

    /**
     * Add forwarding ip rule
     *
     * @param fromIface interface name to add forwarding ip rule
     * @param toLp link properties of the network to add forwarding ip rule
     * @param ailPrefixes list of prefixes to advertise of the AIL
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void addInterfaceForward(final String fromIface, final LinkProperties toLp,
            final Set<IpPrefix> ailPrefixes) throws ServiceSpecificException, RemoteException {
        addInterfaceForward(fromIface, toLp.getInterfaceName(), toLp.getMtu(), ailPrefixes,
                false /* enableNat */);
    }

    private void addInterfaceForward(final String fromIface, final String toIface, final int toMtu,
            final Set<IpPrefix> ailPrefixes, final boolean enableNat)
            throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        synchronized (mIfacesLock) {
            if (mForwardedInterfaces.size() == 0) {
                mNetd.ipfwdEnableForwarding("RoutingCoordinator");
            }
            final RouterAdvertisementDaemon raDaemon;
            if (ailPrefixes.isEmpty()) {
                raDaemon = null;
            } else {
                final InterfaceParams ifaceParams = InterfaceParams.getByName(toIface);
                if (null == ifaceParams) {
                    raDaemon = null;
                } else {
                    raDaemon = new RouterAdvertisementDaemon(ifaceParams);
                    final RaParams raParams = new RaParams();
                    raParams.prefixes.addAll(ailPrefixes);
                    raParams.mtu = toMtu;
                    raDaemon.buildNewRa(null /* deprecatedParams */, raParams);
                    raDaemon.start();
                }
            }
            final ForwardingPair fwp = new ForwardingPair(fromIface, toIface, enableNat, raDaemon);
            // Only from and to are considered by ForwardingPair#equals and #hashcode.
            if (mForwardedInterfaces.contains(fwp)) {
                throw new IllegalStateException("Forward already exists between ifaces "
                        + fromIface + " → " + toIface);
            }
            mForwardedInterfaces.add(fwp);
            if (enableNat) mNetd.tetherAddForward(fromIface, toIface);
            mNetd.ipfwdAddInterfaceForward(fromIface, toIface);
        }
    }

    /**
     * Remove forwarding ip rule
     *
     * @param fromIface interface name to remove forwarding ip rule
     * @param toIface interface name to remove forwarding ip rule
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void removeInterfaceForward(final String fromIface, final String toIface)
            throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        synchronized (mIfacesLock) {
            final ForwardingPair fwp = CollectionUtils.findFirst(mForwardedInterfaces,
                    p -> fromIface.equals(p.fromIface) && toIface.equals(p.toIface));
            if (null == fwp) {
                throw new IllegalStateException("No forward set up between interfaces "
                        + fromIface + " → " + toIface);
            }
            mForwardedInterfaces.remove(fwp);
            mNetd.ipfwdRemoveInterfaceForward(fromIface, toIface);
            if (null != fwp.raDaemon) {
                fwp.raDaemon.stop();
            }
            if (fwp.enableNat) mNetd.tetherRemoveForward(fromIface, toIface);
            if (mForwardedInterfaces.size() == 0) {
                mNetd.ipfwdDisableForwarding("RoutingCoordinator");
            }
        }
    }
}
