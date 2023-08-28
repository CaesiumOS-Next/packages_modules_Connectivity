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

import static android.net.BpfNetMapsConstants.DOZABLE_MATCH;
import static android.net.BpfNetMapsConstants.LOW_POWER_STANDBY_MATCH;
import static android.net.BpfNetMapsConstants.MATCH_LIST;
import static android.net.BpfNetMapsConstants.NO_MATCH;
import static android.net.BpfNetMapsConstants.OEM_DENY_1_MATCH;
import static android.net.BpfNetMapsConstants.OEM_DENY_2_MATCH;
import static android.net.BpfNetMapsConstants.OEM_DENY_3_MATCH;
import static android.net.BpfNetMapsConstants.POWERSAVE_MATCH;
import static android.net.BpfNetMapsConstants.RESTRICTED_MATCH;
import static android.net.BpfNetMapsConstants.STANDBY_MATCH;
import static android.net.BpfNetMapsConstants.UID_RULES_CONFIGURATION_KEY;
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

import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Pair;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.Struct;

import java.util.StringJoiner;

/**
 * The classes and the methods for BpfNetMaps utilization.
 *
 * @hide
 */
// Note that this class should be put into bootclasspath instead of static libraries.
// Because modules could have different copies of this class if this is statically linked,
// which would be problematic if the definitions in these modules are not synchronized.
public class BpfNetMapsUtils {
    // Prevent this class from being accidental instantiated.
    private BpfNetMapsUtils() {}

    /**
     * Get corresponding match from firewall chain.
     */
    public static long getMatchByFirewallChain(final int chain) {
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
    public static boolean isFirewallAllowList(final int chain) {
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

    /**
     * Get match string representation from the given match bitmap.
     */
    public static String matchToString(long matchMask) {
        if (matchMask == NO_MATCH) {
            return "NO_MATCH";
        }

        final StringJoiner sj = new StringJoiner(" ");
        for (final Pair<Long, String> match : MATCH_LIST) {
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

    public static final boolean PRE_T = !SdkLevel.isAtLeastT();

    /**
     * Throw UnsupportedOperationException if SdkLevel is before T.
     */
    public static void throwIfPreT(final String msg) {
        if (PRE_T) {
            throw new UnsupportedOperationException(msg);
        }
    }

    /**
     * Get the specified firewall chain's status.
     *
     * @param configurationMap target configurationMap
     * @param childChain target chain
     * @return {@code true} if chain is enabled, {@code false} if chain is not enabled.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public static boolean isChainEnabled(final IBpfMap<Struct.S32, Struct.U32> configurationMap,
            final int childChain) {
        throwIfPreT("isChainEnabled is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        try {
            final Struct.U32 config = configurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
            return (config.val & match) != 0;
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to get firewall chain status: " + Os.strerror(e.errno));
        }
    }

    /**
     * Get firewall rule of specified firewall chain on specified uid.
     *
     * @param uidOwnerMap target uidOwnerMap
     * @param childChain target chain
     * @param uid        target uid
     * @return either FIREWALL_RULE_ALLOW or FIREWALL_RULE_DENY
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException      in case of failure, with an error code indicating the
     *                                       cause of the failure.
     */
    public static int getUidRule(final IBpfMap<Struct.S32, UidOwnerValue> uidOwnerMap,
            final int childChain, final int uid) {
        throwIfPreT("isUidChainEnabled is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        final boolean isAllowList = isFirewallAllowList(childChain);
        try {
            final UidOwnerValue uidMatch = uidOwnerMap.getValue(new Struct.S32(uid));
            final boolean isMatchEnabled = uidMatch != null && (uidMatch.rule & match) != 0;
            return isMatchEnabled == isAllowList ? FIREWALL_RULE_ALLOW : FIREWALL_RULE_DENY;
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to get uid rule status: " + Os.strerror(e.errno));
        }
    }
}
