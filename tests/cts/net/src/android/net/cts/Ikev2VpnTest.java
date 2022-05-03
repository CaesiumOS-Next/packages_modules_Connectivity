/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.cts;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.cts.util.CtsNetUtils.TestNetworkCallback;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;
import static com.android.modules.utils.build.SdkLevel.isAtLeastT;
import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;
import static com.android.testutils.TestableNetworkCallbackKt.anyNetwork;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Ikev2VpnProfile;
import android.net.IpSecAlgorithm;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.TestNetworkInterface;
import android.net.VpnManager;
import android.net.cts.util.CtsNetUtils;
import android.net.cts.util.IkeSessionTestUtils;
import android.net.ipsec.ike.IkeTunnelConnectionParams;
import android.os.Build;
import android.os.Process;
import android.platform.test.annotations.AppModeFull;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.HexDump;
import com.android.networkstack.apishim.ConstantsShim;
import com.android.networkstack.apishim.VpnManagerShimImpl;
import com.android.networkstack.apishim.common.VpnManagerShim;
import com.android.networkstack.apishim.common.VpnProfileStateShim;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.RecorderCallback.CallbackEntry;
import com.android.testutils.TestableNetworkCallback;

import org.bouncycastle.x509.X509V1CertificateGenerator;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.security.auth.x500.X500Principal;

@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.Q)
@AppModeFull(reason = "Appops state changes disallowed for instant apps (OP_ACTIVATE_PLATFORM_VPN)")
public class Ikev2VpnTest {
    private static final String TAG = Ikev2VpnTest.class.getSimpleName();

    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    // Test vectors for IKE negotiation in test mode.
    private static final String SUCCESSFUL_IKE_INIT_RESP_V4 =
            "46b8eca1e0d72a18b2b5d9006d47a0022120222000000000000002d0220000300000002c01010004030000"
                    + "0c0100000c800e0100030000080300000c030000080200000400000008040000102800020800"
                    + "100000b8070f159fe5141d8754ca86f72ecc28d66f514927e96cbe9eec0adb42bf2c276a0ab7"
                    + "a97fa93555f4be9218c14e7f286bb28c6b4fb13825a420f2ffc165854f200bab37d69c8963d4"
                    + "0acb831d983163aa50622fd35c182efe882cf54d6106222abcfaa597255d302f1b95ab71c142"
                    + "c279ea5839a180070bff73f9d03fab815f0d5ee2adec7e409d1e35979f8bd92ffd8aab13d1a0"
                    + "0657d816643ae767e9ae84d2ccfa2bcce1a50572be8d3748ae4863c41ae90da16271e014270f"
                    + "77edd5cd2e3299f3ab27d7203f93d770bacf816041cdcecd0f9af249033979da4369cb242dd9"
                    + "6d172e60513ff3db02de63e50eb7d7f596ada55d7946cad0af0669d1f3e2804846ab3f2a930d"
                    + "df56f7f025f25c25ada694e6231abbb87ee8cfd072c8481dc0b0f6b083fdc3bd89b080e49feb"
                    + "0288eef6fdf8a26ee2fc564a11e7385215cf2deaf2a9965638fc279c908ccdf04094988d91a2"
                    + "464b4a8c0326533aff5119ed79ecbd9d99a218b44f506a5eb09351e67da86698b4c58718db25"
                    + "d55f426fb4c76471b27a41fbce00777bc233c7f6e842e39146f466826de94f564cad8b92bfbe"
                    + "87c99c4c7973ec5f1eea8795e7da82819753aa7c4fcfdab77066c56b939330c4b0d354c23f83"
                    + "ea82fa7a64c4b108f1188379ea0eb4918ee009d804100e6bf118771b9058d42141c847d5ec37"
                    + "6e5ec591c71fc9dac01063c2bd31f9c783b28bf1182900002430f3d5de3449462b31dd28bc27"
                    + "297b6ad169bccce4f66c5399c6e0be9120166f2900001c0000400428b8df2e66f69c8584a186"
                    + "c5eac66783551d49b72900001c000040054e7a622e802d5cbfb96d5f30a6e433994370173529"
                    + "0000080000402e290000100000402f00020003000400050000000800004014";
    private static final String SUCCESSFUL_IKE_INIT_RESP_V6 =
            "46b8eca1e0d72a1800d9ea1babce26bf2120222000000000000002d0220000300000002c01010004030000"
                    + "0c0100000c800e0100030000080300000c030000080200000400000008040000102800020800"
                    + "100000ea0e6dd9ca5930a9a45c323a41f64bfd8cdef7730f5fbff37d7c377da427f489a42aa8"
                    + "c89233380e6e925990d49de35c2cdcf63a61302c731a4b3569df1ee1bf2457e55a6751838ede"
                    + "abb75cc63ba5c9e4355e8e784f383a5efe8a44727dc14aeaf8dacc2620fb1c8875416dc07739"
                    + "7fe4decc1bd514a9c7d270cf21fd734c63a25c34b30b68686e54e8a198f37f27cb491fe27235"
                    + "fab5476b036d875ccab9a68d65fbf3006197f9bebbf94de0d3802b4fafe1d48d931ce3a1a346"
                    + "2d65bd639e9bd7fa46299650a9dbaf9b324e40b466942d91a59f41ef8042f8474c4850ed0f63"
                    + "e9238949d41cd8bbaea9aefdb65443a6405792839563aa5dc5c36b5ce8326ccf8a94d9622b85"
                    + "038d390d5fc0299e14e1f022966d4ac66515f6108ca04faec44821fe5bbf2ed4f84ff5671219"
                    + "608cb4c36b44a31ba010c9088f8d5ff943bb9ff857f74be1755f57a5783874adc57f42bb174e"
                    + "4ad3215de628707014dbcb1707bd214658118fdd7a42b3e1638b991ce5b812a667f1145be811"
                    + "685e3cd3baf9b18d062657b64c206a4d19a531c252a6a51a04aeaf42c618620cdbab65baca23"
                    + "82c57ed888422aeaacf7f1bc3fe2247ff7e7eaca218b74d7b31d02f2b0afa123f802529e7e6c"
                    + "3259d418290740ddbf55686e26998d7edcbbf895664972fed666f2f20af40503aa2af436ec6d"
                    + "4ec981ab19b9088755d94ae7a7c2066ea331d4e56e290000243fefe5555fce552d57a84e682c"
                    + "d4a6dfb3f2f94a94464d5bec3d88b88e9559642900001c00004004eb4afff764e7b79bca78b1"
                    + "3a89100d36d678ae982900001c00004005d177216a3c26f782076e12570d40bfaaa148822929"
                    + "0000080000402e290000100000402f00020003000400050000000800004014";
    private static final String SUCCESSFUL_IKE_AUTH_RESP_V4 =
            "46b8eca1e0d72a18b2b5d9006d47a0022e20232000000001000000e0240000c420a2500a3da4c66fa6929e"
                    + "600f36349ba0e38de14f78a3ad0416cba8c058735712a3d3f9a0a6ed36de09b5e9e02697e7c4"
                    + "2d210ac86cfbd709503cfa51e2eab8cfdc6427d136313c072968f6506a546eb5927164200592"
                    + "6e36a16ee994e63f029432a67bc7d37ca619e1bd6e1678df14853067ecf816b48b81e8746069"
                    + "406363e5aa55f13cb2afda9dbebee94256c29d630b17dd7f1ee52351f92b6e1c3d8551c513f1"
                    + "d74ac52a80b2041397e109fe0aeb3c105b0d4be0ae343a943398764281";
    private static final String SUCCESSFUL_IKE_AUTH_RESP_V6 =
            "46b8eca1e0d72a1800d9ea1babce26bf2e20232000000001000000f0240000d4aaf6eaa6c06b50447e6f54"
                    + "827fd8a9d9d6ac8015c1ebb3e8cb03fc6e54b49a107441f50004027cc5021600828026367f03"
                    + "bc425821cd7772ee98637361300c9b76056e874fea2bd4a17212370b291894264d8c023a01d1"
                    + "c3b691fd4b7c0b534e8c95af4c4638e2d125cb21c6267e2507cd745d72e8da109c47b9259c6c"
                    + "57a26f6bc5b337b9b9496d54bdde0333d7a32e6e1335c9ee730c3ecd607a8689aa7b0577b74f"
                    + "3bf437696a9fd5fc0aee3ed346cd9e15d1dda293df89eb388a8719388a60ca7625754de12cdb"
                    + "efe4c886c5c401";

    private static final String MOBIKE_IKE_INIT_RESP_V4 =
            "46b8eca1e0d72a1827354a05992f77e12120222000000000000002d8220000300000002c01010004030000"
                    + "0c0100000c800e0100030000080300000c030000080200000400000008040000102800020800"
                    + "100000456ac5b15451deb5990991d7a147de89f595ef908bb07f2642a993e0f0dc699e701d73"
                    + "c3738ba81113e4ac17612b419c15363888ab37970fd65c68363cdb52401b559bf39ba4d5bb94"
                    + "51f55cea7cd480fc3786f73d87a40c8961a27e630bb6e1c8689a7b5f669abbb0790809a9678d"
                    + "ef365cd6f27911cd0cdf6d22ec82612237b961d81936577b951ca30beb17c8f6a9671760ff4f"
                    + "814a48993e7e77e680577bd3d5c2bc397d10efc6a0db547e7f09dba9f081689f3af7d4b54b50"
                    + "2e862594b686b2e1d5b8011b1aa9af725e0c07de3057dc969aab6d6d243f190e0aaf2921622c"
                    + "14c118375e18fd61a38e70e6f4590940d8e74284a41acedcb6f5537e68691be91e7d64d23f08"
                    + "657147c64bb1f5a6e2f2436e4c05f5dd8ad73a41129ddb1cf294196d9b2f1e752a598bc9b6a3"
                    + "5e0f4a4cb8a17013971e26f96635a24d1b687506188fdbeea3f8884d4c0f902fa198a86f960e"
                    + "2977fa647397c90e5dd5b2aa4af9f0755f3ae0b53cfa6c1ca9cf754bdd1db5cc7149676b0763"
                    + "f6a54c758e076a010a31d0e5d1e681dce8b64b41d7695304626845dd9c4d119d15bf13e90c39"
                    + "f6996c4def4750aa45f558947be23d47e5f34a4b837f000a831e9f041da7794fca8f26f0f890"
                    + "f1790c4f72696752845c13ea03ef2335fc56b5491a7ef599ccf16f6eb6fbd1315e2c6aee0466"
                    + "0098a2100a292c26b1683df81421a0bacd2ec7b4ff29000024a81f11b59e93049cfea9dc67cb"
                    + "9061eb354040c8ba533c3c8b18a6e581de0c0b2900001c000040040d315a21566415d5bcf0a7"
                    + "bc5afd38258e42b0b42900001c000040055a138039a2ca3f777db2ecb2779bf2a46f683a2529"
                    + "0000080000402e290000100000402f0002000300040005290000080000402200000008000040"
                    + "14";
    private static final String MOBIKE_IKE_AUTH_RESP_V4 =
            "46b8eca1e0d72a1827354a05992f77e12e2023200000000100000100240000e47ca5b2291164bfc1210d02"
                    + "97c184a130d202781bde408ad96da5c0a5c6fde1fdbcf88d496ee099a49faddbae0045df1e1d"
                    + "1cdc3ef3df2a0fabff56b003acc492c6286a73b5a8726f9ecae8db4937ed3c0c80ff4ae14be9"
                    + "9eb7bbd9629eccbbbd65a00161b5e93762c337128db2e485f81d9732199b63ae190562bb1be4"
                    + "c36a9fecf033444b9ae7103860fe6dc75b4c7afa23e496351c322bc10c38ace06af11c57e2b8"
                    + "463cd35f1a847cf163767ea2998d1cf40dff7de7aeb13b01ca07e47de7fb4bacd65881e04523"
                    + "0de2460c61617a3a58352229613df68e5e764f2a8dde88";
    private static final String MOBIKE_INFO_RESP_V4 =
            "46b8eca1e0d72a1827354a05992f77e12e202520000000020000008029000064f2ab4e8f587b5a7e320b50"
                    + "91b4157c1ab3282147327815f2e7a7cbc70c4f62900fc4b1cb6ddbf58c90672f20fec7eb6897"
                    + "197b272507e352b53d6f4d75521541d34ac1818f7b73766edd2acdf6bfe6ddd2f1a3ecbc2c04"
                    + "611e781a58e8b114f5";
    private static final String MOBIKE_REKEY_CREATE_RESP_V4 =
            "46b8eca1e0d72a1827354a05992f77e12e20242000000003000000c0210000a412c14486e3279555af0e4e"
                    + "15ce9cc38f102addbf3fa05bdb10dfd30a9319435808a2e41995fe9a8568e9e985b1dca11baf"
                    + "a48388123fb62b4d930c41b0991ba893a74b97dfd689a6ac6050faf7bcca8a7ff41c90011de8"
                    + "017f796d556ca12f6625416808ae51de21dcb6100ca1500bc0a64e844c4a699f278a1bb6a148"
                    + "901d4a4e3fa04273ed589bf33bf60a33bb80a86411cb2c2fd32ca5ba546b27ed08f20c";
    private static final String MOBIKE_REKEY_DEL_RESP_V4 =
            "46b8eca1e0d72a1827354a05992f77e12e20252000000004000000502a000034afc399661a664444f794d2"
                    + "e764130bdfa4d5dc6ae04cb3d9beeff86fa5658662e4811a82962dbba6b479775f70a7eafc";

    private static final String MOBIKE_IKE_INIT_RESP_V6 =
            "46b8eca1e0d72a18476c85c56672fed92120222000000000000002a0220000300000002c01010004030000"
                    + "0c0100000c800e0100030000080300000c030000080200000400000008040000102800020800"
                    + "100000db853755a0df24f29ce3f066011d6760ac8f762c2ec47b2c07446cc013a43ad9d7b762"
                    + "f2c8248318528aeaed0b699bc33a374180a5489752e6192fddcfaca3dc78e10f1c090bc62a37"
                    + "1bf5e2a5ed17569fc0906c9ef3e2358621d407edeb156e650c632481d9336594844dd5dc9304"
                    + "efe045f8127125c2123178d046bdcdec5e97facf73450d77637b288723882f6e764f0d07d166"
                    + "28750f4029af6bbe1102dbfd5dc424a8c418a657d3ef6726595b05da6b980f7ab898b6b33acf"
                    + "32e1917d2d667a9e5ee9fa71d032791fa5fd46d8e8f6b5777a7c3d138fe68c1c1d5d5b66aa5a"
                    + "4cdc7a99b4c64a7edb1b863b8de705933cd36252b74458a01c6f99626b964a9180225f2c55d3"
                    + "4f41347f89d00a3981ade9290c059696540b856389ffd937966ed26bef3036b0018eebe65782"
                    + "2b86e36eda203cbeeeb47245507ddb063f7bd1c13ed4a68aba5e7a89989f8c4d84a109aaf0cb"
                    + "94a394c9e585b188f8c72ae567c7d711fff6d938878ff40af31482fef5a31693745cd5473e02"
                    + "b85d5e9f597b1ceeef517bdc1dcb1faa51f8008890b5f4d1f064ec384e2335a5798645d614da"
                    + "15ece5ed2047d7c15b810a4ca1bd6724ee7c1cbebf988530058e639486398a5cffabda9f3091"
                    + "f33a427d632e35d1395d7f9508a97f88094576276e058e1c45b7f623cd56887ee28353e16a1a"
                    + "810028159f399aa989039985ba0c1bfb66932e043629000024da27d4b5e207f8bf62affa2f67"
                    + "93a1b18e73c582c8f9c4700d4fdca2630eee7f290000080000402e290000100000402f000200"
                    + "030004000529000008000040220000000800004014";
    private static final String MOBIKE_IKE_AUTH_RESP_V6 =
            "46b8eca1e0d72a18476c85c56672fed92e2023200000000100000100240000e414d806096bcd24c0196eb8"
                    + "898ea98c721d331cb4f91b2b87750cd300c77b0e8ee0124b9c80a8135484a408e94e7b8d52f9"
                    + "d502728a7bea7ab2264db6d83ab0bb3164ab09dbcbfe02928e1e5de81ec44b1376d5f1c26272"
                    + "c686296b7edf253ffa92dfecdf3068bd4e57ccfcee65f7e448bf74987b5645001b599683431a"
                    + "efa505b06c9751bc00ffc53ea2961791a354d78531f51b037f86ddf9816aeeb32d44f77c2266"
                    + "4a61b99c0230809e9d0cdc2e31db6109756f1d456f7194faa4453dc56a614e0787477d805888"
                    + "eeb02f8b5098e901af57e3ed0a735f6fe6624b498eaec1";
    private static final String MOBIKE_INFO_RESP_V6 =
            "46b8eca1e0d72a18476c85c56672fed92e2025200000000200000050000000345be0bcb81824042c8a6e60"
                    + "8a4bcf2e9ccff4c59c04b798fe5df8c8ba207d15ff899f53110bee2d1e1aaf637b5f4a76ab";
    private static final String MOBIKE_REKEY_CREATE_RESP_V6 =
            "46b8eca1e0d72a18476c85c56672fed92e20242000000003000000c0210000a4b3ae19e3d573efd42881f5"
                    + "f17dd9d2c149d045f4e32d02005765e691f8baee5ef2c3d0abfbaa311cd81ca819a9dff5c2f7"
                    + "1fc0237838e194ba7f0725e549ddecaa55a2db1349b497ea1d0380a4261d23e0e656009a35ae"
                    + "983265f3584e576f0e2ce81f06038214cfa563dc096d031be7d1ae7bf98c71dfb6e845e18150"
                    + "005582283c28ab0444bb86242a8bf3f095e4057a9b49586f1f0fb5029c126ba847e0e0";
    private static final String MOBIKE_REKEY_DEL_RESP_V6 =
            "46b8eca1e0d72a18476c85c56672fed92e20252000000004000000502a0000341d6429a5dbc422af5dbcc7"
                    + "e1fe45cce13e9dcdd5299e995ace45194517fb91866dfe7d279dd69dc72bce7597a81c5c40";

    private static final long IKE_INITIATOR_SPI = Long.parseLong("46B8ECA1E0D72A18", 16);

    private static final InetAddress LOCAL_OUTER_4 = InetAddress.parseNumericAddress("192.0.2.1");
    private static final InetAddress LOCAL_OUTER_6 =
            InetAddress.parseNumericAddress("2001:db8::1");

    private static final InetAddress LOCAL_OUTER_4_NEW =
            InetAddress.parseNumericAddress("192.0.2.100");
    private static final InetAddress LOCAL_OUTER_6_NEW =
            InetAddress.parseNumericAddress("2001:db8::100");

    private static final int IP4_PREFIX_LEN = 32;
    private static final int IP6_PREFIX_LEN = 128;

    // TODO: Use IPv6 address when we can generate test vectors (GCE does not allow IPv6 yet).
    private static final String TEST_SERVER_ADDR_V4 = "192.0.2.2";
    private static final String TEST_SERVER_ADDR_V6 = "2001:db8::2";
    private static final String TEST_IDENTITY = "client.cts.android.com";
    private static final List<String> TEST_ALLOWED_ALGORITHMS =
            Arrays.asList(IpSecAlgorithm.AUTH_CRYPT_AES_GCM);

    private static final ProxyInfo TEST_PROXY_INFO =
            ProxyInfo.buildDirectProxy("proxy.cts.android.com", 1234);
    private static final int TEST_MTU = 1300;

    private static final byte[] TEST_PSK = "ikeAndroidPsk".getBytes();
    private static final String TEST_USER = "username";
    private static final String TEST_PASSWORD = "pa55w0rd";

    // Static state to reduce setup/teardown
    private static final Context sContext = InstrumentationRegistry.getContext();
    private static final ConnectivityManager sCM =
            (ConnectivityManager) sContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    private static final VpnManager sVpnMgr =
            (VpnManager) sContext.getSystemService(Context.VPN_MANAGEMENT_SERVICE);
    private static final CtsNetUtils mCtsNetUtils = new CtsNetUtils(sContext);
    private static final long TIMEOUT_MS = 15_000;

    private VpnManagerShim mVmShim = VpnManagerShimImpl.newInstance(sContext);

    private final X509Certificate mServerRootCa;
    private final CertificateAndKey mUserCertKey;
    private final List<TestableNetworkCallback> mCallbacksToUnregister = new ArrayList<>();

    public Ikev2VpnTest() throws Exception {
        // Build certificates
        mServerRootCa = generateRandomCertAndKeyPair().cert;
        mUserCertKey = generateRandomCertAndKeyPair();
    }

    @After
    public void tearDown() {
        for (TestableNetworkCallback callback : mCallbacksToUnregister) {
            sCM.unregisterNetworkCallback(callback);
        }
        setAppop(AppOpsManager.OP_ACTIVATE_VPN, false);
        setAppop(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, false);
    }

    /**
     * Sets the given appop using shell commands
     *
     * <p>This method must NEVER be called from within a shell permission, as it will attempt to
     * acquire, and then drop the shell permission identity. This results in the caller losing the
     * shell permission identity due to these calls not being reference counted.
     */
    public void setAppop(int appop, boolean allow) {
        // Requires shell permission to update appops.
        runWithShellPermissionIdentity(() -> {
            mCtsNetUtils.setAppopPrivileged(appop, allow);
        }, Manifest.permission.MANAGE_TEST_NETWORKS);
    }

    private Ikev2VpnProfile buildIkev2VpnProfileCommon(
            @NonNull Ikev2VpnProfile.Builder builder, boolean isRestrictedToTestNetworks,
            boolean requiresValidation) throws Exception {

        builder.setBypassable(true)
                .setAllowedAlgorithms(TEST_ALLOWED_ALGORITHMS)
                .setProxy(TEST_PROXY_INFO)
                .setMaxMtu(TEST_MTU)
                .setMetered(false);
        if (TestUtils.shouldTestTApis()) {
            builder.setRequiresInternetValidation(requiresValidation);
        }
        if (isRestrictedToTestNetworks) {
            builder.restrictToTestNetworks();
        }

        return builder.build();
    }

    private Ikev2VpnProfile buildIkev2VpnProfileIkeTunConnParams(
            final boolean isRestrictedToTestNetworks, final boolean requiresValidation,
            final boolean testIpv6) throws Exception {
        final IkeTunnelConnectionParams params =
                new IkeTunnelConnectionParams(testIpv6
                        ? IkeSessionTestUtils.IKE_PARAMS_V6 : IkeSessionTestUtils.IKE_PARAMS_V4,
                        IkeSessionTestUtils.CHILD_PARAMS);

        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(params)
                        .setRequiresInternetValidation(requiresValidation)
                        .setProxy(TEST_PROXY_INFO)
                        .setMaxMtu(TEST_MTU)
                        .setMetered(false);

        if (isRestrictedToTestNetworks) {
            builder.restrictToTestNetworks();
        }
        return builder.build();
    }

    private Ikev2VpnProfile buildIkev2VpnProfilePsk(@NonNull String remote,
            boolean isRestrictedToTestNetworks, boolean requiresValidation) throws Exception {
        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(remote, TEST_IDENTITY).setAuthPsk(TEST_PSK);
        return buildIkev2VpnProfileCommon(builder, isRestrictedToTestNetworks,
                requiresValidation);
    }

    private Ikev2VpnProfile buildIkev2VpnProfileUsernamePassword(boolean isRestrictedToTestNetworks)
            throws Exception {

        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(TEST_SERVER_ADDR_V6, TEST_IDENTITY)
                        .setAuthUsernamePassword(TEST_USER, TEST_PASSWORD, mServerRootCa);
        return buildIkev2VpnProfileCommon(builder, isRestrictedToTestNetworks,
                false /* requiresValidation */);
    }

    private Ikev2VpnProfile buildIkev2VpnProfileDigitalSignature(boolean isRestrictedToTestNetworks)
            throws Exception {
        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(TEST_SERVER_ADDR_V6, TEST_IDENTITY)
                        .setAuthDigitalSignature(
                                mUserCertKey.cert, mUserCertKey.key, mServerRootCa);
        return buildIkev2VpnProfileCommon(builder, isRestrictedToTestNetworks,
                false /* requiresValidation */);
    }

    private void checkBasicIkev2VpnProfile(@NonNull Ikev2VpnProfile profile) throws Exception {
        assertEquals(TEST_SERVER_ADDR_V6, profile.getServerAddr());
        assertEquals(TEST_IDENTITY, profile.getUserIdentity());
        assertEquals(TEST_PROXY_INFO, profile.getProxyInfo());
        assertEquals(TEST_ALLOWED_ALGORITHMS, profile.getAllowedAlgorithms());
        assertTrue(profile.isBypassable());
        assertFalse(profile.isMetered());
        assertEquals(TEST_MTU, profile.getMaxMtu());
        assertFalse(profile.isRestrictedToTestNetworks());
    }

    public void doTestBuildIkev2VpnProfilePsk(final boolean requiresValidation) throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        final Ikev2VpnProfile profile = buildIkev2VpnProfilePsk(TEST_SERVER_ADDR_V6,
                false /* isRestrictedToTestNetworks */, requiresValidation);

        checkBasicIkev2VpnProfile(profile);
        assertArrayEquals(TEST_PSK, profile.getPresharedKey());

        // Verify nothing else is set.
        assertNull(profile.getUsername());
        assertNull(profile.getPassword());
        assertNull(profile.getServerRootCaCert());
        assertNull(profile.getRsaPrivateKey());
        assertNull(profile.getUserCert());
        if (isAtLeastT()) {
            assertEquals(requiresValidation, profile.isInternetValidationRequired());
        }
    }

    @IgnoreUpTo(SC_V2)
    @Test
    public void testBuildIkev2VpnProfileWithIkeTunnelConnectionParams() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        assumeTrue(TestUtils.shouldTestTApis());

        final IkeTunnelConnectionParams expectedParams = new IkeTunnelConnectionParams(
                IkeSessionTestUtils.IKE_PARAMS_V6, IkeSessionTestUtils.CHILD_PARAMS);
        final Ikev2VpnProfile.Builder ikeProfileBuilder =
                new Ikev2VpnProfile.Builder(expectedParams);
        // Verify the other Ike options could not be set with IkeTunnelConnectionParams.
        final Class<IllegalArgumentException> expected = IllegalArgumentException.class;
        assertThrows(expected, () -> ikeProfileBuilder.setAuthPsk(TEST_PSK));
        assertThrows(expected, () ->
                ikeProfileBuilder.setAuthUsernamePassword(TEST_USER, TEST_PASSWORD, mServerRootCa));
        assertThrows(expected, () -> ikeProfileBuilder.setAuthDigitalSignature(
                mUserCertKey.cert, mUserCertKey.key, mServerRootCa));

        final Ikev2VpnProfile profile = ikeProfileBuilder.build();

        assertEquals(expectedParams, profile.getIkeTunnelConnectionParams());
    }

    @Test
    public void testBuildIkev2VpnProfilePsk() throws Exception {
        doTestBuildIkev2VpnProfilePsk(true /* requiresValidation */);
        doTestBuildIkev2VpnProfilePsk(false /* requiresValidation */);
    }

    @Test
    public void testBuildIkev2VpnProfileUsernamePassword() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        final Ikev2VpnProfile profile =
                buildIkev2VpnProfileUsernamePassword(false /* isRestrictedToTestNetworks */);

        checkBasicIkev2VpnProfile(profile);
        assertEquals(TEST_USER, profile.getUsername());
        assertEquals(TEST_PASSWORD, profile.getPassword());
        assertEquals(mServerRootCa, profile.getServerRootCaCert());

        // Verify nothing else is set.
        assertNull(profile.getPresharedKey());
        assertNull(profile.getRsaPrivateKey());
        assertNull(profile.getUserCert());
    }

    @Test
    public void testBuildIkev2VpnProfileDigitalSignature() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        final Ikev2VpnProfile profile =
                buildIkev2VpnProfileDigitalSignature(false /* isRestrictedToTestNetworks */);

        checkBasicIkev2VpnProfile(profile);
        assertEquals(mUserCertKey.cert, profile.getUserCert());
        assertEquals(mUserCertKey.key, profile.getRsaPrivateKey());
        assertEquals(mServerRootCa, profile.getServerRootCaCert());

        // Verify nothing else is set.
        assertNull(profile.getUsername());
        assertNull(profile.getPassword());
        assertNull(profile.getPresharedKey());
    }

    private void verifyProvisionVpnProfile(
            boolean hasActivateVpn, boolean hasActivatePlatformVpn, boolean expectIntent)
            throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        setAppop(AppOpsManager.OP_ACTIVATE_VPN, hasActivateVpn);
        setAppop(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, hasActivatePlatformVpn);

        final Ikev2VpnProfile profile = buildIkev2VpnProfilePsk(TEST_SERVER_ADDR_V6,
                false /* isRestrictedToTestNetworks */, false /* requiresValidation */);
        final Intent intent = sVpnMgr.provisionVpnProfile(profile);
        assertEquals(expectIntent, intent != null);
    }

    @Test
    public void testProvisionVpnProfileNoPreviousConsent() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        verifyProvisionVpnProfile(false /* hasActivateVpn */,
                false /* hasActivatePlatformVpn */, true /* expectIntent */);
    }

    @Test
    public void testProvisionVpnProfilePlatformVpnConsented() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        verifyProvisionVpnProfile(false /* hasActivateVpn */,
                true /* hasActivatePlatformVpn */, false /* expectIntent */);
    }

    @Test
    public void testProvisionVpnProfileVpnServiceConsented() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        verifyProvisionVpnProfile(true /* hasActivateVpn */,
                false /* hasActivatePlatformVpn */, false /* expectIntent */);
    }

    @Test
    public void testProvisionVpnProfileAllPreConsented() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        verifyProvisionVpnProfile(true /* hasActivateVpn */,
                true /* hasActivatePlatformVpn */, false /* expectIntent */);
    }

    @Test
    public void testDeleteVpnProfile() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        setAppop(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, true);

        final Ikev2VpnProfile profile = buildIkev2VpnProfilePsk(TEST_SERVER_ADDR_V6,
                false /* isRestrictedToTestNetworks */, false /* requiresValidation */);
        assertNull(sVpnMgr.provisionVpnProfile(profile));

        // Verify that deleting the profile works (even without the appop)
        setAppop(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, false);
        sVpnMgr.deleteProvisionedVpnProfile();

        // Test that the profile was deleted - starting it should throw an IAE.
        try {
            setAppop(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, true);
            sVpnMgr.startProvisionedVpnProfile();
            fail("Expected IllegalArgumentException due to missing profile");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testStartVpnProfileNoPreviousConsent() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        setAppop(AppOpsManager.OP_ACTIVATE_VPN, false);
        setAppop(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, false);

        // Make sure the VpnProfile is not provisioned already.
        sVpnMgr.stopProvisionedVpnProfile();

        try {
            sVpnMgr.startProvisionedVpnProfile();
            fail("Expected SecurityException for missing consent");
        } catch (SecurityException expected) {
        }
    }

    private void checkStartStopVpnProfileBuildsNetworks(
            @NonNull IkeTunUtils tunUtils,
            boolean testIpv6,
            boolean requiresValidation,
            boolean testSessionKey,
            boolean testIkeTunConnParams,
            boolean shouldTeardownVpnNetwork,
            String testInitResp,
            String testAuthResp)
            throws Exception {
        String serverAddr = testIpv6 ? TEST_SERVER_ADDR_V6 : TEST_SERVER_ADDR_V4;
        boolean hasNat = !testIpv6;

        String initResp = testInitResp;
        String authResp = testAuthResp;

        // Requires MANAGE_TEST_NETWORKS to provision a test-mode profile.
        mCtsNetUtils.setAppopPrivileged(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, true);

        final Ikev2VpnProfile profile = testIkeTunConnParams
                ? buildIkev2VpnProfileIkeTunConnParams(true /* isRestrictedToTestNetworks */,
                        requiresValidation, testIpv6)
                : buildIkev2VpnProfilePsk(serverAddr, true /* isRestrictedToTestNetworks */,
                        requiresValidation);
        assertNull(sVpnMgr.provisionVpnProfile(profile));

        final TestableNetworkCallback cb = new TestableNetworkCallback(TIMEOUT_MS);
        final NetworkRequest nr = new NetworkRequest.Builder()
                .clearCapabilities().addTransportType(TRANSPORT_VPN).build();
        registerNetworkCallback(nr, cb);

        if (testSessionKey) {
            // testSessionKey will never be true if running on <T
            // startProvisionedVpnProfileSession() should return a non-null & non-empty random UUID.
            final String sessionId = mVmShim.startProvisionedVpnProfileSession();
            assertFalse(TextUtils.isEmpty(sessionId));
            final VpnProfileStateShim profileState = mVmShim.getProvisionedVpnProfileState();
            assertNotNull(profileState);
            assertEquals(ConstantsShim.VPN_PROFILE_STATE_CONNECTING, profileState.getState());
            assertEquals(sessionId, profileState.getSessionId());
            assertFalse(profileState.isAlwaysOn());
            assertFalse(profileState.isLockdownEnabled());
        } else {
            sVpnMgr.startProvisionedVpnProfile();
        }

        // Inject IKE negotiation
        int expectedMsgId = 0;
        tunUtils.awaitReqAndInjectResp(IKE_INITIATOR_SPI, expectedMsgId++, false /* isEncap */,
                HexDump.hexStringToByteArray(initResp));
        tunUtils.awaitReqAndInjectResp(IKE_INITIATOR_SPI, expectedMsgId++, hasNat /* isEncap */,
                HexDump.hexStringToByteArray(authResp));

        // Verify the VPN network came up
        final Network vpnNetwork = cb.expectCallback(CallbackEntry.AVAILABLE, anyNetwork())
                .getNetwork();

        if (testSessionKey) {
            final VpnProfileStateShim profileState = mVmShim.getProvisionedVpnProfileState();
            assertNotNull(profileState);
            assertEquals(ConstantsShim.VPN_PROFILE_STATE_CONNECTED, profileState.getState());
            assertFalse(profileState.isAlwaysOn());
            assertFalse(profileState.isLockdownEnabled());
        }

        cb.expectCapabilitiesThat(vpnNetwork, TIMEOUT_MS,
                caps -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasCapability(NET_CAPABILITY_INTERNET)
                && !caps.hasCapability(NET_CAPABILITY_VALIDATED)
                && Process.myUid() == caps.getOwnerUid());
        cb.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, vpnNetwork);
        cb.expectCallback(CallbackEntry.BLOCKED_STATUS, vpnNetwork);

        // A VPN that requires validation is initially not validated, while one that doesn't
        // immediately validate automatically. Because this VPN can't actually access Internet,
        // the VPN only validates if it doesn't require validation. If the VPN requires validation
        // but unexpectedly sends this callback, expecting LOST below will fail because the next
        // callback will be the validated capabilities instead.
        // In S and below, |requiresValidation| is ignored, so this callback is always sent
        // regardless of its value. However, there is a race in Vpn(see b/228574221) that VPN may
        // misuse VPN network itself as the underlying network. The fix is not available without
        // SDK > T platform. Thus, verify this only on T+ platform.
        if (!requiresValidation && TestUtils.shouldTestTApis()) {
            cb.eventuallyExpect(CallbackEntry.NETWORK_CAPS_UPDATED, TIMEOUT_MS,
                    entry -> ((CallbackEntry.CapabilitiesChanged) entry).getCaps()
                            .hasCapability(NET_CAPABILITY_VALIDATED));
        }

        if (shouldTeardownVpnNetwork) {
            sVpnMgr.stopProvisionedVpnProfile();
            // Using expectCallback may cause the test to be flaky since test may receive other
            // callbacks such as linkproperties change.
            cb.eventuallyExpect(
                    CallbackEntry.LOST, TIMEOUT_MS, lost -> vpnNetwork.equals(lost.getNetwork()));
        }
    }

    private void checkStartStopVpnProfileBuildsAndTearsDownNetwork(
            @NonNull IkeTunUtils tunUtils,
            boolean testIpv6,
            boolean requiresValidation,
            boolean testSessionKey,
            boolean testIkeTunConnParams)
            throws Exception {

        String initResp = testIpv6 ? SUCCESSFUL_IKE_INIT_RESP_V6 : SUCCESSFUL_IKE_INIT_RESP_V4;
        String authResp = testIpv6 ? SUCCESSFUL_IKE_AUTH_RESP_V6 : SUCCESSFUL_IKE_AUTH_RESP_V4;

        checkStartStopVpnProfileBuildsNetworks(
                tunUtils,
                testIpv6,
                requiresValidation,
                testSessionKey,
                testIkeTunConnParams,
                true /* shouldTeardownVpnNetwork */,
                initResp,
                authResp);
    }

    private void checkStartVpnProfileBuildsNetworks(
            @NonNull IkeTunUtils tunUtils,
            boolean testIpv6,
            boolean testIkeTunConnParams,
            String initResp,
            String authResp)
            throws Exception {
        checkStartStopVpnProfileBuildsNetworks(
                tunUtils,
                testIpv6,
                false /* requiresValidation */,
                false /* testSessionKey */,
                testIkeTunConnParams,
                false /* shouldTeardownVpnNetwork */,
                initResp,
                authResp);
    }

    private void checkVpnHandlesNetworkChange(boolean isMobikeEnabled, boolean isIpv6,
            boolean testIkeTunConnParams, String... respHex) throws Exception {

        InetAddress localAddress = isIpv6 ? LOCAL_OUTER_6 : LOCAL_OUTER_4;
        InetAddress newLocalAddress = isIpv6 ? LOCAL_OUTER_6_NEW : LOCAL_OUTER_4_NEW;

        try (TestNetworkWrapper testNetworkWrapper = new TestNetworkWrapper(localAddress)) {
            checkStartVpnProfileBuildsNetworks(
                    testNetworkWrapper.tunUtils,
                    isIpv6 /* testIpv6 */,
                    testIkeTunConnParams,
                    respHex[0],
                    respHex[1]);

            // Upon network change, VPN is expected to migrate or re-setup
            try (TestNetworkWrapper newTestNetworkWrapper =
                    new TestNetworkWrapper(newLocalAddress)) {
                testNetworkWrapper.close();

                final IkeTunUtils newIkeTunUtils = newTestNetworkWrapper.tunUtils;

                if (isMobikeEnabled) {
                    // IKE setup costs two exchanges
                    final int startingExpectedMsgId = 2;

                    // Verify that IKE migrates to the new network
                    newIkeTunUtils.awaitReqAndInjectRespSeries(
                            IKE_INITIATOR_SPI,
                            startingExpectedMsgId,
                            true /* encapExpected */,
                            Arrays.copyOfRange(respHex, 2, respHex.length - 1));
                } else {
                    int expectedMsgId = 0;

                    // Verify that IKE setup triggered on new network
                    newIkeTunUtils.awaitReqAndInjectResp(
                            IKE_INITIATOR_SPI,
                            expectedMsgId++,
                            false /* encapExpected */,
                            HexDump.hexStringToByteArray(respHex[2]));
                    newIkeTunUtils.awaitReqAndInjectResp(
                            IKE_INITIATOR_SPI,
                            expectedMsgId++,
                            !isIpv6,
                            HexDump.hexStringToByteArray(respHex[3]));
                }
            } finally {
                sVpnMgr.stopProvisionedVpnProfile();
            }
        }

    }

    private void registerNetworkCallback(NetworkRequest request, TestableNetworkCallback callback) {
        sCM.registerNetworkCallback(request, callback);
        mCallbacksToUnregister.add(callback);
    }

    private class VerifyStartStopVpnProfileTest implements TestNetworkRunnable.Test {
        private final boolean mTestIpv6Only;
        private final boolean mRequiresValidation;
        private final boolean mTestSessionKey;
        private final boolean mTestIkeTunConnParams;

        /**
         * Constructs the test
         *
         * @param testIpv6Only if true, builds a IPv6-only test; otherwise builds a IPv4-only test
         * @param requiresValidation whether this VPN should request platform validation
         * @param testSessionKey if true, start VPN by calling startProvisionedVpnProfileSession()
         */
        VerifyStartStopVpnProfileTest(boolean testIpv6Only, boolean requiresValidation,
                boolean testSessionKey, boolean testIkeTunConnParams) {
            mTestIpv6Only = testIpv6Only;
            mRequiresValidation = requiresValidation;
            mTestSessionKey = testSessionKey;
            mTestIkeTunConnParams = testIkeTunConnParams;
        }

        @Override
        public void runTest(TestNetworkInterface testIface, TestNetworkCallback tunNetworkCallback)
                throws Exception {
            final IkeTunUtils tunUtils = new IkeTunUtils(testIface.getFileDescriptor());

            checkStartStopVpnProfileBuildsAndTearsDownNetwork(tunUtils, mTestIpv6Only,
                    mRequiresValidation, mTestSessionKey, mTestIkeTunConnParams);
        }

        @Override
        public void cleanupTest() {
            sVpnMgr.stopProvisionedVpnProfile();
        }

        @Override
        public InetAddress[] getTestNetworkAddresses() {
            if (mTestIpv6Only) {
                return new InetAddress[] {LOCAL_OUTER_6};
            } else {
                return new InetAddress[] {LOCAL_OUTER_4};
            }
        }
    }

    private void doTestStartStopVpnProfile(boolean testIpv6Only, boolean requiresValidation,
            boolean testSessionKey, boolean testIkeTunConnParams) throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        // Requires shell permission to update appops.
        runWithShellPermissionIdentity(
                new TestNetworkRunnable(new VerifyStartStopVpnProfileTest(
                        testIpv6Only, requiresValidation, testSessionKey , testIkeTunConnParams)));
    }

    private void checkVpnMigratesWithMobike(boolean isIpv6, boolean testIkeTunConnParams)
            throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        runWithShellPermissionIdentity(
                () -> {
                    if (isIpv6) {
                        checkVpnHandlesNetworkChange(
                                true /* isMobikeEnabled */,
                                true /* isIpv6 */,
                                testIkeTunConnParams,
                                MOBIKE_IKE_INIT_RESP_V6,
                                MOBIKE_IKE_AUTH_RESP_V6,
                                MOBIKE_INFO_RESP_V6,
                                MOBIKE_REKEY_CREATE_RESP_V6,
                                MOBIKE_REKEY_DEL_RESP_V6);
                    } else {
                        checkVpnHandlesNetworkChange(
                                true /* isMobikeEnabled */,
                                false /* isIpv6 */,
                                testIkeTunConnParams,
                                MOBIKE_IKE_INIT_RESP_V4,
                                MOBIKE_IKE_AUTH_RESP_V4,
                                MOBIKE_INFO_RESP_V4,
                                MOBIKE_REKEY_CREATE_RESP_V4,
                                MOBIKE_REKEY_DEL_RESP_V4);
                    }

                });
    }

    @Test
    public void testVpnMigratesWithMobikeV4() throws Exception {
        checkVpnMigratesWithMobike(false /* isIpv6 */, false /* testIkeTunConnParams */);
    }

    @Test
    public void testVpnMigratesWithMobikeV4WithIkeTunnelConnectionParams() throws Exception {
        checkVpnMigratesWithMobike(false /* isIpv6 */, true /* testIkeTunConnParams */);
    }

    @Test
    public void testVpnMigratesWithMobikeV6() throws Exception {
        checkVpnMigratesWithMobike(true /* isIpv6 */, false /* testIkeTunConnParams */);
    }

    @Test
    public void testVpnMigratesWithMobikeV6WithIkeTunnelConnectionParams() throws Exception {
        checkVpnMigratesWithMobike(true /* isIpv6 */, true /* testIkeTunConnParams */);
    }

    private void checkVpnReestablishesWithoutMobike(boolean isIpv6, boolean testIkeTunConnParams)
            throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        runWithShellPermissionIdentity(
                () -> {
                    if (isIpv6) {
                        checkVpnHandlesNetworkChange(
                                false /* isMobikeEnabled */,
                                true /* isIpv6 */,
                                testIkeTunConnParams,
                                SUCCESSFUL_IKE_INIT_RESP_V6,
                                SUCCESSFUL_IKE_AUTH_RESP_V6,
                                SUCCESSFUL_IKE_INIT_RESP_V6,
                                SUCCESSFUL_IKE_AUTH_RESP_V6);
                    } else {
                        checkVpnHandlesNetworkChange(
                                false /* isMobikeEnabled */,
                                false /* isIpv6 */,
                                testIkeTunConnParams,
                                SUCCESSFUL_IKE_INIT_RESP_V4,
                                SUCCESSFUL_IKE_AUTH_RESP_V4,
                                SUCCESSFUL_IKE_INIT_RESP_V4,
                                SUCCESSFUL_IKE_AUTH_RESP_V4);
                    }

                });
    }

    @Test
    public void testVpnReestablishesV4() throws Exception {
        checkVpnReestablishesWithoutMobike(false /* isIpv6 */, false /* testIkeTunConnParams */);
    }

    @Test
    public void testVpnReestablishesV4WithIkeTunnelConnectionParams() throws Exception {
        checkVpnReestablishesWithoutMobike(false /* isIpv6 */, true /* testIkeTunConnParams */);
    }

    @Test
    public void testVpnReestablishes() throws Exception {
        checkVpnReestablishesWithoutMobike(true /* isIpv6 */, false /* testIkeTunConnParams */);
    }

    @Test
    public void testVpnReestablishesWithIkeTunnelConnectionParams() throws Exception {
        checkVpnReestablishesWithoutMobike(true /* isIpv6 */, true /* testIkeTunConnParams */);
    }

    @Test
    public void testStartStopVpnProfileV4() throws Exception {
        doTestStartStopVpnProfile(false /* testIpv6Only */, false /* requiresValidation */,
                false /* testSessionKey */, false /* testIkeTunConnParams */);
    }

    @Test @IgnoreUpTo(SC_V2)
    public void testStartStopVpnProfileV4WithValidation() throws Exception {
        assumeTrue(TestUtils.shouldTestTApis());
        doTestStartStopVpnProfile(false /* testIpv6Only */, true /* requiresValidation */,
                false /* testSessionKey */, false /* testIkeTunConnParams */);
    }

    @Test
    public void testStartStopVpnProfileV6() throws Exception {
        doTestStartStopVpnProfile(true /* testIpv6Only */, false /* requiresValidation */,
                false /* testSessionKey */, false /* testIkeTunConnParams */);
    }

    @Test @IgnoreUpTo(SC_V2)
    public void testStartStopVpnProfileV6WithValidation() throws Exception {
        assumeTrue(TestUtils.shouldTestTApis());
        doTestStartStopVpnProfile(true /* testIpv6Only */, true /* requiresValidation */,
                false /* testSessionKey */, false /* testIkeTunConnParams */);
    }

    @Test @IgnoreUpTo(SC_V2)
    public void testStartStopVpnProfileIkeTunConnParamsV4() throws Exception {
        assumeTrue(TestUtils.shouldTestTApis());
        doTestStartStopVpnProfile(false /* testIpv6Only */, false /* requiresValidation */,
                false /* testSessionKey */, true /* testIkeTunConnParams */);
    }

    @Test @IgnoreUpTo(SC_V2)
    public void testStartStopVpnProfileIkeTunConnParamsV4WithValidation() throws Exception {
        assumeTrue(TestUtils.shouldTestTApis());
        doTestStartStopVpnProfile(false /* testIpv6Only */, true /* requiresValidation */,
                false /* testSessionKey */, true /* testIkeTunConnParams */);
    }

    @Test @IgnoreUpTo(SC_V2)
    public void testStartStopVpnProfileIkeTunConnParamsV6() throws Exception {
        assumeTrue(TestUtils.shouldTestTApis());
        doTestStartStopVpnProfile(true /* testIpv6Only */, false /* requiresValidation */,
                false /* testSessionKey */, true /* testIkeTunConnParams */);
    }

    @Test @IgnoreUpTo(SC_V2)
    public void testStartStopVpnProfileIkeTunConnParamsV6WithValidation() throws Exception {
        assumeTrue(TestUtils.shouldTestTApis());
        doTestStartStopVpnProfile(true /* testIpv6Only */, true /* requiresValidation */,
                false /* testSessionKey */, true /* testIkeTunConnParams */);
    }

    @IgnoreUpTo(SC_V2)
    @Test
    public void testStartProvisionedVpnV4ProfileSession() throws Exception {
        assumeTrue(TestUtils.shouldTestTApis());
        doTestStartStopVpnProfile(false /* testIpv6Only */, false /* requiresValidation */,
                true /* testSessionKey */, false /* testIkeTunConnParams */);
    }

    @IgnoreUpTo(SC_V2)
    @Test
    public void testStartProvisionedVpnV6ProfileSession() throws Exception {
        assumeTrue(TestUtils.shouldTestTApis());
        doTestStartStopVpnProfile(true /* testIpv6Only */, false /* requiresValidation */,
                true /* testSessionKey */, false /* testIkeTunConnParams */);
    }

    private static class CertificateAndKey {
        public final X509Certificate cert;
        public final PrivateKey key;

        CertificateAndKey(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }
    }

    private static CertificateAndKey generateRandomCertAndKeyPair() throws Exception {
        final Date validityBeginDate =
                new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1L));
        final Date validityEndDate =
                new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1L));

        // Generate a keypair
        final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(512);
        final KeyPair keyPair = keyPairGenerator.generateKeyPair();

        final X500Principal dnName = new X500Principal("CN=test.android.com");
        final X509V1CertificateGenerator certGen = new X509V1CertificateGenerator();
        certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()));
        certGen.setSubjectDN(dnName);
        certGen.setIssuerDN(dnName);
        certGen.setNotBefore(validityBeginDate);
        certGen.setNotAfter(validityEndDate);
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");

        final X509Certificate cert = certGen.generate(keyPair.getPrivate(), "AndroidOpenSSL");
        return new CertificateAndKey(cert, keyPair.getPrivate());
    }
}
