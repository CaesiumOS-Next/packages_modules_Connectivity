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

package android.net;

import static android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_2;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_RULE_ALLOW;
import static android.net.ConnectivityManager.FIREWALL_RULE_DENY;
import static android.system.OsConstants.EINVAL;
import static android.system.OsConstants.ENODEV;
import static android.system.OsConstants.ENOENT;
import static android.system.OsConstants.EOPNOTSUPP;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;


import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfDump;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.Struct.S32;
import com.android.net.module.util.Struct.U32;
import com.android.net.module.util.Struct.U8;
import com.android.net.module.util.bpf.CookieTagMapKey;
import com.android.net.module.util.bpf.CookieTagMapValue;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Base class of BpfNetMaps.
 *
 * This class only contains the basic functionalities of Java Bpf support which provides
 * direct Bpf access in ConnectivityManager.
 * Because this class would live in bootclasspath. Compares to full functionalities that
 * provided by BpfNetMaps, this class takes fewer of dependencies that are needed, which would
 * otherwise increase the memory usage of each process.
 *
 * {@hide}
 */
public class BpfNetMapsBase {
    protected static final boolean PRE_T = !SdkLevel.isAtLeastT();

    protected static final String TAG = "BpfNetMaps";
    private final Dependencies mDeps;
    // Use legacy netd for releases before T.
    private static boolean sInitialized = false;

    // Lock for sConfigurationMap entry for UID_RULES_CONFIGURATION_KEY.
    // This entry is not accessed by others.
    // BpfNetMaps acquires this lock while sequence of read, modify, and write.
    private static final Object sUidRulesConfigBpfMapLock = new Object();

    // Lock for sConfigurationMap entry for CURRENT_STATS_MAP_CONFIGURATION_KEY.
    // BpfNetMaps acquires this lock while sequence of read, modify, and write.
    // BpfNetMaps is an only writer of this entry.
    protected static final Object sCurrentStatsMapConfigLock = new Object();

    private static final String CONFIGURATION_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_configuration_map";
    private static final String UID_OWNER_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_uid_owner_map";
    private static final String UID_PERMISSION_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_uid_permission_map";
    private static final String COOKIE_TAG_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_cookie_tag_map";
    protected static final S32 UID_RULES_CONFIGURATION_KEY = new S32(0);
    protected static final S32 CURRENT_STATS_MAP_CONFIGURATION_KEY = new S32(1);
    protected static final long UID_RULES_DEFAULT_CONFIGURATION = 0;
    protected static final long STATS_SELECT_MAP_A = 0;
    protected static final long STATS_SELECT_MAP_B = 1;

    protected static IBpfMap<S32, U32> sConfigurationMap = null;
    // BpfMap for UID_OWNER_MAP_PATH. This map is not accessed by others.
    protected static IBpfMap<S32, UidOwnerValue> sUidOwnerMap = null;
    protected static IBpfMap<S32, U8> sUidPermissionMap = null;
    protected static IBpfMap<CookieTagMapKey, CookieTagMapValue> sCookieTagMap = null;

    // LINT.IfChange(match_type)
    @VisibleForTesting public static final long NO_MATCH = 0;
    @VisibleForTesting public static final long HAPPY_BOX_MATCH = (1 << 0);
    @VisibleForTesting public static final long PENALTY_BOX_MATCH = (1 << 1);
    @VisibleForTesting public static final long DOZABLE_MATCH = (1 << 2);
    @VisibleForTesting public static final long STANDBY_MATCH = (1 << 3);
    @VisibleForTesting public static final long POWERSAVE_MATCH = (1 << 4);
    @VisibleForTesting public static final long RESTRICTED_MATCH = (1 << 5);
    @VisibleForTesting public static final long LOW_POWER_STANDBY_MATCH = (1 << 6);
    @VisibleForTesting public static final long IIF_MATCH = (1 << 7);
    @VisibleForTesting public static final long LOCKDOWN_VPN_MATCH = (1 << 8);
    @VisibleForTesting public static final long OEM_DENY_1_MATCH = (1 << 9);
    @VisibleForTesting public static final long OEM_DENY_2_MATCH = (1 << 10);
    @VisibleForTesting public static final long OEM_DENY_3_MATCH = (1 << 11);
    // LINT.ThenChange(packages/modules/Connectivity/bpf_progs/netd.h)

    protected static final List<Pair<Long, String>> MATCH_LIST = Arrays.asList(
            Pair.create(HAPPY_BOX_MATCH, "HAPPY_BOX_MATCH"),
            Pair.create(PENALTY_BOX_MATCH, "PENALTY_BOX_MATCH"),
            Pair.create(DOZABLE_MATCH, "DOZABLE_MATCH"),
            Pair.create(STANDBY_MATCH, "STANDBY_MATCH"),
            Pair.create(POWERSAVE_MATCH, "POWERSAVE_MATCH"),
            Pair.create(RESTRICTED_MATCH, "RESTRICTED_MATCH"),
            Pair.create(LOW_POWER_STANDBY_MATCH, "LOW_POWER_STANDBY_MATCH"),
            Pair.create(IIF_MATCH, "IIF_MATCH"),
            Pair.create(LOCKDOWN_VPN_MATCH, "LOCKDOWN_VPN_MATCH"),
            Pair.create(OEM_DENY_1_MATCH, "OEM_DENY_1_MATCH"),
            Pair.create(OEM_DENY_2_MATCH, "OEM_DENY_2_MATCH"),
            Pair.create(OEM_DENY_3_MATCH, "OEM_DENY_3_MATCH")
    );

    /**
     * Set configurationMap for test.
     */
    @VisibleForTesting
    public static void setConfigurationMapForTest(IBpfMap<S32, U32> configurationMap) {
        sConfigurationMap = configurationMap;
    }

    /**
     * Set uidOwnerMap for test.
     */
    @VisibleForTesting
    public static void setUidOwnerMapForTest(IBpfMap<S32, UidOwnerValue> uidOwnerMap) {
        sUidOwnerMap = uidOwnerMap;
    }

    /**
     * Set uidPermissionMap for test.
     */
    @VisibleForTesting
    public static void setUidPermissionMapForTest(IBpfMap<S32, U8> uidPermissionMap) {
        sUidPermissionMap = uidPermissionMap;
    }

    /**
     * Set cookieTagMap for test.
     */
    @VisibleForTesting
    public static void setCookieTagMapForTest(
            IBpfMap<CookieTagMapKey, CookieTagMapValue> cookieTagMap) {
        sCookieTagMap = cookieTagMap;
    }

    private static IBpfMap<S32, U32> getConfigurationMap() {
        try {
            return new BpfMap<>(
                    CONFIGURATION_MAP_PATH, BpfMap.BPF_F_RDWR, S32.class, U32.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open netd configuration map", e);
        }
    }

    private static IBpfMap<S32, UidOwnerValue> getUidOwnerMap() {
        try {
            return new BpfMap<>(
                    UID_OWNER_MAP_PATH, BpfMap.BPF_F_RDWR, S32.class, UidOwnerValue.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open uid owner map", e);
        }
    }

    private static IBpfMap<S32, U8> getUidPermissionMap() {
        try {
            return new BpfMap<>(
                    UID_PERMISSION_MAP_PATH, BpfMap.BPF_F_RDWR, S32.class, U8.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open uid permission map", e);
        }
    }

    private static IBpfMap<CookieTagMapKey, CookieTagMapValue> getCookieTagMap() {
        try {
            return new BpfMap<>(COOKIE_TAG_MAP_PATH, BpfMap.BPF_F_RDWR,
                    CookieTagMapKey.class, CookieTagMapValue.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open cookie tag map", e);
        }
    }

    // This is called directly from framework-connectivity to read bpf maps and indirectly from
    // services by BpfNetMaps to initialize maps with default values. Thus, do not update
    // entries with default values here.
    protected static void initBpfMaps() {
        if (sConfigurationMap == null) {
            sConfigurationMap = getConfigurationMap();
        }

        if (sUidOwnerMap == null) {
            sUidOwnerMap = getUidOwnerMap();
        }

        if (sUidPermissionMap == null) {
            sUidPermissionMap = getUidPermissionMap();
        }

        if (sCookieTagMap == null) {
            sCookieTagMap = getCookieTagMap();
        }
    }

    /**
     * Initializes the class if it is not already initialized. This method will open maps but not
     * cause any other effects. This method may be called multiple times on any thread.
     */
    private static synchronized void ensureInitialized(final Context context) {
        if (sInitialized) return;

        initBpfMaps();
        sInitialized = true;
    }

    /**
     * Dependencies of BpfNetMaps, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Get interface index.
         */
        public int getIfIndex(final String ifName) {
            return Os.if_nametoindex(ifName);
        }
    }

    public BpfNetMapsBase(final Context context) {
        this(context, new Dependencies());
        if (PRE_T) throw new IllegalArgumentException("BpfNetMaps need to use netd before T");
    }

    @VisibleForTesting
    public BpfNetMapsBase(final Context context, final Dependencies deps) {
        if (!PRE_T) {
            ensureInitialized(context);
        }
        mDeps = deps;
    }

    /**
     * Get corresponding match from firewall chain.
     */
    @VisibleForTesting
    public long getMatchByFirewallChain(final int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_DOZABLE:
                return DOZABLE_MATCH;
            case FIREWALL_CHAIN_STANDBY:
                return STANDBY_MATCH;
            case FIREWALL_CHAIN_POWERSAVE:
                return POWERSAVE_MATCH;
            case FIREWALL_CHAIN_RESTRICTED:
                return RESTRICTED_MATCH;
            case FIREWALL_CHAIN_LOW_POWER_STANDBY:
                return LOW_POWER_STANDBY_MATCH;
            case FIREWALL_CHAIN_OEM_DENY_1:
                return OEM_DENY_1_MATCH;
            case FIREWALL_CHAIN_OEM_DENY_2:
                return OEM_DENY_2_MATCH;
            case FIREWALL_CHAIN_OEM_DENY_3:
                return OEM_DENY_3_MATCH;
            default:
                throw new ServiceSpecificException(EINVAL, "Invalid firewall chain: " + chain);
        }
    }

    /**
     * Get if the chain is allow list or not.
     *
     * ALLOWLIST means the firewall denies all by default, uids must be explicitly allowed
     * DENYLIST means the firewall allows all by default, uids must be explicitly denyed
     */
    public boolean isFirewallAllowList(final int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_DOZABLE:
            case FIREWALL_CHAIN_POWERSAVE:
            case FIREWALL_CHAIN_RESTRICTED:
            case FIREWALL_CHAIN_LOW_POWER_STANDBY:
                return true;
            case FIREWALL_CHAIN_STANDBY:
            case FIREWALL_CHAIN_OEM_DENY_1:
            case FIREWALL_CHAIN_OEM_DENY_2:
            case FIREWALL_CHAIN_OEM_DENY_3:
                return false;
            default:
                throw new ServiceSpecificException(EINVAL, "Invalid firewall chain: " + chain);
        }
    }

    protected void maybeThrow(final int err, final String msg) {
        if (err != 0) {
            throw new ServiceSpecificException(err, msg + ": " + Os.strerror(err));
        }
    }

    protected void throwIfPreT(final String msg) {
        if (PRE_T) {
            throw new UnsupportedOperationException(msg);
        }
    }

    protected void removeRule(final int uid, final long match, final String caller) {
        try {
            synchronized (sUidOwnerMap) {
                final UidOwnerValue oldMatch = sUidOwnerMap.getValue(new S32(uid));

                if (oldMatch == null) {
                    throw new ServiceSpecificException(ENOENT,
                            "sUidOwnerMap does not have entry for uid: " + uid);
                }

                final UidOwnerValue newMatch = new UidOwnerValue(
                        (match == IIF_MATCH) ? 0 : oldMatch.iif,
                        oldMatch.rule & ~match
                );

                if (newMatch.rule == 0) {
                    sUidOwnerMap.deleteEntry(new S32(uid));
                } else {
                    sUidOwnerMap.updateEntry(new S32(uid), newMatch);
                }
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    caller + " failed to remove rule: " + Os.strerror(e.errno));
        }
    }

    private void addRule(final int uid, final long match, final int iif, final String caller) {
        throwIfPreT("addRule is not available on pre-T devices");

        if (match != IIF_MATCH && iif != 0) {
            throw new ServiceSpecificException(EINVAL,
                    "Non-interface match must have zero interface index");
        }

        try {
            synchronized (sUidOwnerMap) {
                final UidOwnerValue oldMatch = sUidOwnerMap.getValue(new S32(uid));

                final UidOwnerValue newMatch;
                if (oldMatch != null) {
                    newMatch = new UidOwnerValue(
                            (match == IIF_MATCH) ? iif : oldMatch.iif,
                            oldMatch.rule | match
                    );
                } else {
                    newMatch = new UidOwnerValue(
                            iif,
                            match
                    );
                }
                sUidOwnerMap.updateEntry(new S32(uid), newMatch);
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    caller + " failed to add rule: " + Os.strerror(e.errno));
        }
    }

    protected void addRule(final int uid, final long match, final String caller) {
        addRule(uid, match, 0 /* iif */, caller);
    }

    /**
     * Add naughty app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addNaughtyApp(final int uid) {
        throwIfPreT("addNaughtyApp is not available on pre-T devices");
        addRule(uid, PENALTY_BOX_MATCH, "addNaughtyApp");
    }

    /**
     * Remove naughty app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeNaughtyApp(final int uid) {
        throwIfPreT("removeNaughtyApp is not available on pre-T devices");
        removeRule(uid, PENALTY_BOX_MATCH, "removeNaughtyApp");
    }

    /**
     * Add nice app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addNiceApp(final int uid) {
        throwIfPreT("addNiceApp is not available on pre-T devices");
        addRule(uid, HAPPY_BOX_MATCH, "addNiceApp");
    }

    /**
     * Remove nice app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeNiceApp(final int uid) {
        throwIfPreT("removeNiceApp is not available on pre-T devices");
        removeRule(uid, HAPPY_BOX_MATCH, "removeNiceApp");
    }

    /**
     * Set target firewall child chain
     *
     * @param childChain target chain to enable
     * @param enable     whether to enable or disable child chain.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setChildChain(final int childChain, final boolean enable) {
        throwIfPreT("setChildChain is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        try {
            synchronized (sUidRulesConfigBpfMapLock) {
                final U32 config = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
                final long newConfig = enable ? (config.val | match) : (config.val & ~match);
                sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(newConfig));
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to set child chain: " + Os.strerror(e.errno));
        }
    }

    /**
     * Get the specified firewall chain's status.
     *
     * @param childChain target chain
     * @return {@code true} if chain is enabled, {@code false} if chain is not enabled.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public boolean isChainEnabled(final int childChain) {
        throwIfPreT("isChainEnabled is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        try {
            final U32 config = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
            return (config.val & match) != 0;
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to get firewall chain status: " + Os.strerror(e.errno));
        }
    }

    private Set<Integer> asSet(final int[] uids) {
        final Set<Integer> uidSet = new ArraySet<>();
        for (final int uid: uids) {
            uidSet.add(uid);
        }
        return uidSet;
    }

    /**
     * Replaces the contents of the specified UID-based firewall chain.
     * Enables the chain for specified uids and disables the chain for non-specified uids.
     *
     * @param chain       Target chain.
     * @param uids        The list of UIDs to allow/deny.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws IllegalArgumentException if {@code chain} is not a valid chain.
     */
    public void replaceUidChain(final int chain, final int[] uids) {
        throwIfPreT("replaceUidChain is not available on pre-T devices");

        final long match;
        try {
            match = getMatchByFirewallChain(chain);
        } catch (ServiceSpecificException e) {
            // Throws IllegalArgumentException to keep the behavior of
            // ConnectivityManager#replaceFirewallChain API
            throw new IllegalArgumentException("Invalid firewall chain: " + chain);
        }
        final Set<Integer> uidSet = asSet(uids);
        final Set<Integer> uidSetToRemoveRule = new ArraySet<>();
        try {
            synchronized (sUidOwnerMap) {
                sUidOwnerMap.forEach((uid, config) -> {
                    // config could be null if there is a concurrent entry deletion.
                    // http://b/220084230. But sUidOwnerMap update must be done while holding a
                    // lock, so this should not happen.
                    if (config == null) {
                        Log.wtf(TAG, "sUidOwnerMap entry was deleted while holding a lock");
                    } else if (!uidSet.contains((int) uid.val) && (config.rule & match) != 0) {
                        uidSetToRemoveRule.add((int) uid.val);
                    }
                });

                for (final int uid : uidSetToRemoveRule) {
                    removeRule(uid, match, "replaceUidChain");
                }
                for (final int uid : uids) {
                    addRule(uid, match, "replaceUidChain");
                }
            }
        } catch (ErrnoException | ServiceSpecificException e) {
            Log.e(TAG, "replaceUidChain failed: " + e);
        }
    }

    /**
     * Set firewall rule for uid
     *
     * @param childChain   target chain
     * @param uid          uid to allow/deny
     * @param firewallRule either FIREWALL_RULE_ALLOW or FIREWALL_RULE_DENY
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setUidRule(final int childChain, final int uid, final int firewallRule) {
        throwIfPreT("setUidRule is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        final boolean isAllowList = isFirewallAllowList(childChain);
        final boolean add = (firewallRule == FIREWALL_RULE_ALLOW && isAllowList)
                || (firewallRule == FIREWALL_RULE_DENY && !isAllowList);

        if (add) {
            addRule(uid, match, "setUidRule");
        } else {
            removeRule(uid, match, "setUidRule");
        }
    }

    /**
     * Get firewall rule of specified firewall chain on specified uid.
     *
     * @param childChain target chain
     * @param uid        target uid
     * @return either FIREWALL_RULE_ALLOW or FIREWALL_RULE_DENY
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public int getUidRule(final int childChain, final int uid) {
        throwIfPreT("isUidChainEnabled is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        final boolean isAllowList = isFirewallAllowList(childChain);
        try {
            final UidOwnerValue uidMatch = sUidOwnerMap.getValue(new S32(uid));
            final boolean isMatchEnabled = uidMatch != null && (uidMatch.rule & match) != 0;
            return isMatchEnabled == isAllowList ? FIREWALL_RULE_ALLOW : FIREWALL_RULE_DENY;
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to get uid rule status: " + Os.strerror(e.errno));
        }
    }

    private Set<Integer> getUidsMatchEnabled(final int childChain) throws ErrnoException {
        final long match = getMatchByFirewallChain(childChain);
        Set<Integer> uids = new ArraySet<>();
        synchronized (sUidOwnerMap) {
            sUidOwnerMap.forEach((uid, val) -> {
                if (val == null) {
                    Log.wtf(TAG, "sUidOwnerMap entry was deleted while holding a lock");
                } else {
                    if ((val.rule & match) != 0) {
                        uids.add(uid.val);
                    }
                }
            });
        }
        return uids;
    }

    /**
     * Get uids that has FIREWALL_RULE_ALLOW on allowlist chain.
     * Allowlist means the firewall denies all by default, uids must be explicitly allowed.
     *
     * Note that uids that has FIREWALL_RULE_DENY on allowlist chain can not be computed from the
     * bpf map, since all the uids that does not have explicit FIREWALL_RULE_ALLOW rule in bpf map
     * are determined to have FIREWALL_RULE_DENY.
     *
     * @param childChain target chain
     * @return Set of uids
     */
    public Set<Integer> getUidsWithAllowRuleOnAllowListChain(final int childChain)
            throws ErrnoException {
        if (!isFirewallAllowList(childChain)) {
            throw new IllegalArgumentException("getUidsWithAllowRuleOnAllowListChain is called with"
                    + " denylist chain:" + childChain);
        }
        // Corresponding match is enabled for uids that has FIREWALL_RULE_ALLOW on allowlist chain.
        return getUidsMatchEnabled(childChain);
    }

    /**
     * Get uids that has FIREWALL_RULE_DENY on denylist chain.
     * Denylist means the firewall allows all by default, uids must be explicitly denyed
     *
     * Note that uids that has FIREWALL_RULE_ALLOW on denylist chain can not be computed from the
     * bpf map, since all the uids that does not have explicit FIREWALL_RULE_DENY rule in bpf map
     * are determined to have the FIREWALL_RULE_ALLOW.
     *
     * @param childChain target chain
     * @return Set of uids
     */
    public Set<Integer> getUidsWithDenyRuleOnDenyListChain(final int childChain)
            throws ErrnoException {
        if (isFirewallAllowList(childChain)) {
            throw new IllegalArgumentException("getUidsWithDenyRuleOnDenyListChain is called with"
                    + " allowlist chain:" + childChain);
        }
        // Corresponding match is enabled for uids that has FIREWALL_RULE_DENY on denylist chain.
        return getUidsMatchEnabled(childChain);
    }

    /**
     * Add ingress interface filtering rules to a list of UIDs
     *
     * For a given uid, once a filtering rule is added, the kernel will only allow packets from the
     * allowed interface and loopback to be sent to the list of UIDs.
     *
     * Calling this method on one or more UIDs with an existing filtering rule but a different
     * interface name will result in the filtering rule being updated to allow the new interface
     * instead. Otherwise calling this method will not affect existing rules set on other UIDs.
     *
     * @param ifName the name of the interface on which the filtering rules will allow packets to
     *               be received.
     * @param uids   an array of UIDs which the filtering rules will be set
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addUidInterfaceRules(final String ifName, final int[] uids) throws RemoteException {
        throwIfPreT("addUidInterfaceRules is not available on pre-T devices");

        // Null ifName is a wildcard to allow apps to receive packets on all interfaces and
        // ifIndex is set to 0.
        final int ifIndex;
        if (ifName == null) {
            ifIndex = 0;
        } else {
            ifIndex = mDeps.getIfIndex(ifName);
            if (ifIndex == 0) {
                throw new ServiceSpecificException(ENODEV,
                        "Failed to get index of interface " + ifName);
            }
        }
        for (final int uid : uids) {
            try {
                addRule(uid, IIF_MATCH, ifIndex, "addUidInterfaceRules");
            } catch (ServiceSpecificException e) {
                Log.e(TAG, "addRule failed uid=" + uid + " ifName=" + ifName + ", " + e);
            }
        }
    }

    /**
     * Remove ingress interface filtering rules from a list of UIDs
     *
     * Clear the ingress interface filtering rules from the list of UIDs which were previously set
     * by addUidInterfaceRules(). Ignore any uid which does not have filtering rule.
     *
     * @param uids an array of UIDs from which the filtering rules will be removed
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeUidInterfaceRules(final int[] uids) throws RemoteException {
        throwIfPreT("removeUidInterfaceRules is not available on pre-T devices");

        for (final int uid : uids) {
            try {
                removeRule(uid, IIF_MATCH, "removeUidInterfaceRules");
            } catch (ServiceSpecificException e) {
                Log.e(TAG, "removeRule failed uid=" + uid + ", " + e);
            }
        }
    }

    /**
     * Update lockdown rule for uid
     *
     * @param  uid          target uid to add/remove the rule
     * @param  add          {@code true} to add the rule, {@code false} to remove the rule.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void updateUidLockdownRule(final int uid, final boolean add) {
        throwIfPreT("updateUidLockdownRule is not available on pre-T devices");

        if (add) {
            addRule(uid, LOCKDOWN_VPN_MATCH, "updateUidLockdownRule");
        } else {
            removeRule(uid, LOCKDOWN_VPN_MATCH, "updateUidLockdownRule");
        }
    }

    private String matchToString(long matchMask) {
        if (matchMask == NO_MATCH) {
            return "NO_MATCH";
        }

        final StringJoiner sj = new StringJoiner(" ");
        for (Pair<Long, String> match: MATCH_LIST) {
            final long matchFlag = match.first;
            final String matchName = match.second;
            if ((matchMask & matchFlag) != 0) {
                sj.add(matchName);
                matchMask &= ~matchFlag;
            }
        }
        if (matchMask != 0) {
            sj.add("UNKNOWN_MATCH(" + matchMask + ")");
        }
        return sj.toString();
    }

    private void dumpOwnerMatchConfig(final IndentingPrintWriter pw) {
        try {
            final long match = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val;
            pw.println("current ownerMatch configuration: " + match + " " + matchToString(match));
        } catch (ErrnoException e) {
            pw.println("Failed to read ownerMatch configuration: " + e);
        }
    }

    private void dumpCurrentStatsMapConfig(final IndentingPrintWriter pw) {
        try {
            final long config = sConfigurationMap.getValue(CURRENT_STATS_MAP_CONFIGURATION_KEY).val;
            final String currentStatsMap =
                    (config == STATS_SELECT_MAP_A) ? "SELECT_MAP_A" : "SELECT_MAP_B";
            pw.println("current statsMap configuration: " + config + " " + currentStatsMap);
        } catch (ErrnoException e) {
            pw.println("Falied to read current statsMap configuration: " + e);
        }
    }

    /**
     * Dump BPF maps
     *
     * @param pw print writer
     * @param fd file descriptor to output
     * @param verbose verbose dump flag, if true dump the BpfMap contents
     * @throws IOException when file descriptor is invalid.
     * @throws ServiceSpecificException when the method is called on an unsupported device.
     */
    public void dump(final IndentingPrintWriter pw, final FileDescriptor fd, boolean verbose)
            throws IOException, ServiceSpecificException {
        if (PRE_T) {
            throw new ServiceSpecificException(
                    EOPNOTSUPP, "dumpsys connectivity trafficcontroller dump not available on pre-T"
                    + " devices, use dumpsys netd trafficcontroller instead.");
        }

        if (verbose) {
            pw.println();
            pw.println("BPF map content:");
            pw.increaseIndent();

            dumpOwnerMatchConfig(pw);
            dumpCurrentStatsMapConfig(pw);
            pw.println();

            // TODO: Remove CookieTagMap content dump
            // NetworkStatsService also dumps CookieTagMap and NetworkStatsService is a right place
            // to dump CookieTagMap. But the TagSocketTest in CTS depends on this dump so the tests
            // need to be updated before remove the dump from BpfNetMaps.
            BpfDump.dumpMap(sCookieTagMap, pw, "sCookieTagMap",
                    (key, value) -> "cookie=" + key.socketCookie
                            + " tag=0x" + Long.toHexString(value.tag)
                            + " uid=" + value.uid);
            BpfDump.dumpMap(sUidOwnerMap, pw, "sUidOwnerMap",
                    (uid, match) -> {
                        if ((match.rule & IIF_MATCH) != 0) {
                            // TODO: convert interface index to interface name by IfaceIndexNameMap
                            return uid.val + " " + matchToString(match.rule) + " " + match.iif;
                        } else {
                            return uid.val + " " + matchToString(match.rule);
                        }
                    });
            pw.decreaseIndent();
        }
    }
}
