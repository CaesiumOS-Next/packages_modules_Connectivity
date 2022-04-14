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

package android.net.cts.util;

import static android.net.ipsec.ike.SaProposal.DH_GROUP_2048_BIT_MODP;
import static android.net.ipsec.ike.SaProposal.DH_GROUP_3072_BIT_MODP;
import static android.net.ipsec.ike.SaProposal.DH_GROUP_4096_BIT_MODP;
import static android.net.ipsec.ike.SaProposal.DH_GROUP_CURVE_25519;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CBC;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CTR;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_8;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_CHACHA20_POLY1305;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_AES_CMAC_96;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_AES_XCBC_96;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_AES_128;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_AES_192;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_AES_256;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_UNUSED;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_AES128_CMAC;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_SHA2_384;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512;

import android.net.Ikev2VpnProfile;
import android.net.InetAddresses;
import android.net.IpSecAlgorithm;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeIpv6AddrIdentification;
import android.net.ipsec.ike.IkeSaProposal;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.TunnelModeChildSessionParams;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared testing parameters and util methods for testing IKE */
public class IkeSessionTestUtils {
    public static final String TEST_SERVER_ADDR_V6 = "2001:db8::2";
    public static final String TEST_IDENTITY = "client.cts.android.com";
    public static final byte[] TEST_PSK = "ikeAndroidPsk".getBytes();
    public static final List<String> TEST_ALLOWED_ALGORITHMS =
            Arrays.asList(IpSecAlgorithm.AUTH_CRYPT_AES_GCM);

    public static final IkeSessionParams IKE_PARAMS = getTestIkeSessionParams();

    public static final TunnelModeChildSessionParams CHILD_PARAMS = getChildSessionParams();

    private static TunnelModeChildSessionParams getChildSessionParams() {
        final TunnelModeChildSessionParams.Builder childOptionsBuilder =
                new TunnelModeChildSessionParams.Builder();

        for (final ChildSaProposal childProposal : getChildSaProposals(TEST_ALLOWED_ALGORITHMS)) {
            childOptionsBuilder.addSaProposal(childProposal);
        }
        return childOptionsBuilder.build();
    }

    private static IkeSessionParams getTestIkeSessionParams() {
        final InetAddress addr = InetAddresses.parseNumericAddress(TEST_SERVER_ADDR_V6);
        final IkeSessionParams.Builder ikeOptionsBuilder =
                new IkeSessionParams.Builder()
                        .setServerHostname(TEST_SERVER_ADDR_V6)
                        .setLocalIdentification(new IkeFqdnIdentification(TEST_IDENTITY))
                        .setRemoteIdentification(new IkeIpv6AddrIdentification((Inet6Address) addr))
                        .setAuthPsk(TEST_PSK);

        for (final IkeSaProposal ikeProposal : getIkeSaProposals()) {
            ikeOptionsBuilder.addSaProposal(ikeProposal);
        }

        return ikeOptionsBuilder.build();
    }

    private static List<IkeSaProposal> getIkeSaProposals() {
        final List<IkeSaProposal> proposals = new ArrayList<>();

        final IkeSaProposal.Builder normalModeBuilder = new IkeSaProposal.Builder();

        // Add normal mode encryption algorithms
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CTR, KEY_LEN_AES_256);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_256);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CTR, KEY_LEN_AES_192);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_192);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CTR, KEY_LEN_AES_128);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_128);

        // Authentication/Integrity Algorithms
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_512_256);
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_384_192);
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128);
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_AES_XCBC_96);
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_AES_CMAC_96);

        // Add AEAD options
        final IkeSaProposal.Builder aeadBuilder = new IkeSaProposal.Builder();
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_CHACHA20_POLY1305, KEY_LEN_UNUSED);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_256);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_256);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_256);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_192);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_192);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_192);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_128);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_128);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_128);

        // Add dh, prf for both builders
        for (final IkeSaProposal.Builder builder : Arrays.asList(normalModeBuilder, aeadBuilder)) {
            builder.addDhGroup(DH_GROUP_4096_BIT_MODP);

            // Curve25519 has the same security strength as MODP 3072 and cost less bytes
            builder.addDhGroup(DH_GROUP_CURVE_25519);

            builder.addDhGroup(DH_GROUP_3072_BIT_MODP);
            builder.addDhGroup(DH_GROUP_2048_BIT_MODP);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_512);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_384);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_256);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_AES128_XCBC);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_AES128_CMAC);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_HMAC_SHA1);
        }

        proposals.add(normalModeBuilder.build());
        proposals.add(aeadBuilder.build());
        return proposals;
    }

    private static List<ChildSaProposal> getChildSaProposals(List<String> allowedAlgorithms) {
        final List<ChildSaProposal> proposals = new ArrayList<>();

        final List<Integer> aesKeyLenOptions =
                Arrays.asList(KEY_LEN_AES_256, KEY_LEN_AES_192, KEY_LEN_AES_128);

        // Add non-AEAD options
        if (Ikev2VpnProfile.hasNormalModeAlgorithms(allowedAlgorithms)) {
            final ChildSaProposal.Builder normalModeBuilder = new ChildSaProposal.Builder();

            // Encryption Algorithms:
            // AES-CBC and AES_CTR are currently the only supported encryption algorithms.
            for (int len : aesKeyLenOptions) {
                if (allowedAlgorithms.contains(IpSecAlgorithm.CRYPT_AES_CTR)) {
                    normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CTR, len);
                }
                if (allowedAlgorithms.contains(IpSecAlgorithm.CRYPT_AES_CBC)) {
                    normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, len);
                }
            }

            // Authentication/Integrity Algorithms:
            // Guaranteed by Ikev2VpnProfile constructor to contain at least one of these.
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_HMAC_SHA512)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_512_256);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_HMAC_SHA384)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_384_192);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_HMAC_SHA256)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_AES_XCBC)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_AES_XCBC_96);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_AES_CMAC)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_AES_CMAC_96);
            }

            ChildSaProposal proposal = normalModeBuilder.build();
            if (proposal.getIntegrityAlgorithms().isEmpty()) {
                // Should be impossible; Verified in Ikev2VpnProfile.
            } else {
                proposals.add(normalModeBuilder.build());
            }
        }

        // Add AEAD options
        if (Ikev2VpnProfile.hasAeadAlgorithms(allowedAlgorithms)) {
            final ChildSaProposal.Builder aeadBuilder = new ChildSaProposal.Builder();

            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_CRYPT_CHACHA20_POLY1305)) {
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_CHACHA20_POLY1305, KEY_LEN_UNUSED);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_CRYPT_AES_GCM)) {
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_256);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_256);
                aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_256);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_192);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_192);
                aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_192);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_128);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_128);
                aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_128);
            }

            proposals.add(aeadBuilder.build());
        }

        return proposals;
    }
}
