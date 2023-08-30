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

package android.net.thread.cts;

import static android.net.thread.ActiveOperationalDataset.CHANNEL_PAGE_2P4GHZ;
import static android.net.thread.ActiveOperationalDataset.TYPE_ACTIVE_TIMESTAMP;
import static android.net.thread.ActiveOperationalDataset.TYPE_CHANNEL;
import static android.net.thread.ActiveOperationalDataset.TYPE_CHANNEL_MASK;
import static android.net.thread.ActiveOperationalDataset.TYPE_EXTENDED_PANID;
import static android.net.thread.ActiveOperationalDataset.TYPE_MESH_LOCAL_PREFIX;
import static android.net.thread.ActiveOperationalDataset.TYPE_NETWORK_KEY;
import static android.net.thread.ActiveOperationalDataset.TYPE_NETWORK_NAME;
import static android.net.thread.ActiveOperationalDataset.TYPE_PANID;
import static android.net.thread.ActiveOperationalDataset.TYPE_PSKC;
import static android.net.thread.ActiveOperationalDataset.TYPE_SECURITY_POLICY;

import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.IpPrefix;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ActiveOperationalDataset.Builder;
import android.net.thread.ActiveOperationalDataset.SecurityPolicy;
import android.net.thread.OperationalDatasetTimestamp;
import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.primitives.Bytes;
import com.google.common.testing.EqualsTester;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

/** Tests for {@link ActiveOperationalDataset}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class ActiveOperationalDatasetTest {

    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 19
    // Channel Mask: 0x07FFF800
    // Ext PAN ID: ACC214689BC40BDF
    // Mesh Local Prefix: fd64:db12:25f4:7e0b::/64
    // Network Key: F26B3153760F519A63BAFDDFFC80D2AF
    // Network Name: OpenThread-d9a0
    // PAN ID: 0xD9A0
    // PSKc: A245479C836D551B9CA557F7B9D351B4
    // Security Policy: 672 onrcb
    private static final byte[] VALID_DATASET =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");

    @Mock private Random mockRandom;
    @Mock private SecureRandom mockSecureRandom;

    private static byte[] removeTlv(byte[] dataset, int type) {
        ByteArrayOutputStream os = new ByteArrayOutputStream(dataset.length);
        int i = 0;
        while (i < dataset.length) {
            int ty = dataset[i++] & 0xff;
            byte length = dataset[i++];
            byte[] value = Arrays.copyOfRange(dataset, i, i + length);
            if (ty != type) {
                os.write(ty);
                os.write(length);
                os.writeBytes(value);
            }
            i += length;
        }
        return os.toByteArray();
    }

    private static byte[] addTlv(byte[] dataset, String tlvHex) {
        return Bytes.concat(dataset, base16().decode(tlvHex));
    }

    private static byte[] replaceTlv(byte[] dataset, int type, String newTlvHex) {
        return addTlv(removeTlv(dataset, type), newTlvHex);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void parcelable_parcelingIsLossLess() {
        ActiveOperationalDataset dataset = ActiveOperationalDataset.fromThreadTlvs(VALID_DATASET);

        assertParcelingIsLossless(dataset);
    }

    @Test
    public void fromThreadTlvs_tooLongTlv_throwsIllegalArgument() {
        byte[] invalidTlv = new byte[255];
        invalidTlv[0] = (byte) 0xff;
        invalidTlv[1] = (byte) 0xfd;

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidNetworkKeyTlv_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_NETWORK_KEY, "05080000000000000000");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_noNetworkKeyTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_NETWORK_KEY);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidActiveTimestampTlv_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_ACTIVE_TIMESTAMP, "0E0700000000010000");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_noActiveTimestampTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_ACTIVE_TIMESTAMP);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidNetworkNameTlv_emptyName_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_NETWORK_NAME, "0300");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidNetworkNameTlv_tooLongName_throwsIllegalArgument() {
        byte[] invalidTlv =
                replaceTlv(
                        VALID_DATASET, TYPE_NETWORK_NAME, "03114142434445464748494A4B4C4D4E4F5051");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_noNetworkNameTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_NETWORK_NAME);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidChannelTlv_channelMissing_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_CHANNEL, "000100");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_undefinedChannelPage_success() {
        byte[] datasetTlv = replaceTlv(VALID_DATASET, TYPE_CHANNEL, "0003010020");

        ActiveOperationalDataset dataset = ActiveOperationalDataset.fromThreadTlvs(datasetTlv);

        assertThat(dataset.getChannelPage()).isEqualTo(0x01);
        assertThat(dataset.getChannel()).isEqualTo(0x20);
    }

    @Test
    public void fromThreadTlvs_invalid2P4GhzChannel_throwsIllegalArgument() {
        byte[] invalidTlv1 = replaceTlv(VALID_DATASET, TYPE_CHANNEL, "000300000A");
        byte[] invalidTlv2 = replaceTlv(VALID_DATASET, TYPE_CHANNEL, "000300001B");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv2));
    }

    @Test
    public void fromThreadTlvs_valid2P4GhzChannelTlv_success() {
        byte[] validTlv = replaceTlv(VALID_DATASET, TYPE_CHANNEL, "0003000010");

        ActiveOperationalDataset dataset = ActiveOperationalDataset.fromThreadTlvs(validTlv);

        assertThat(dataset.getChannel()).isEqualTo(16);
    }

    @Test
    public void fromThreadTlvs_noChannelTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_CHANNEL);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_prematureEndOfChannelMaskEntry_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_CHANNEL_MASK, "350100");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_inconsistentChannelMaskLength_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_CHANNEL_MASK, "3506000500010000");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_unsupportedChannelMaskLength_success() {
        ActiveOperationalDataset dataset =
                ActiveOperationalDataset.fromThreadTlvs(
                        replaceTlv(VALID_DATASET, TYPE_CHANNEL_MASK, "350700050001000000"));

        SparseArray<byte[]> channelMask = dataset.getChannelMask();
        assertThat(channelMask.size()).isEqualTo(1);
        assertThat(channelMask.get(CHANNEL_PAGE_2P4GHZ))
                .isEqualTo(new byte[] {0x00, 0x01, 0x00, 0x00, 0x00});
    }

    @Test
    public void fromThreadTlvs_noChannelMaskTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_CHANNEL_MASK);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidPanIdTlv_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_PANID, "010101");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_noPanIdTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_PANID);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidExtendedPanIdTlv_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_EXTENDED_PANID, "020700010203040506");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_noExtendedPanIdTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_EXTENDED_PANID);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidPskcTlv_throwsIllegalArgument() {
        byte[] invalidTlv =
                replaceTlv(VALID_DATASET, TYPE_PSKC, "0411000102030405060708090A0B0C0D0E0F10");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_noPskcTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_PSKC);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidMeshLocalPrefixTlv_throwsIllegalArgument() {
        byte[] invalidTlv =
                replaceTlv(VALID_DATASET, TYPE_MESH_LOCAL_PREFIX, "0709FD0001020304050607");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_noMeshLocalPrefixTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_MESH_LOCAL_PREFIX);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_tooShortSecurityPolicyTlv_throwsIllegalArgument() {
        byte[] invalidTlv = replaceTlv(VALID_DATASET, TYPE_SECURITY_POLICY, "0C0101");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_noSecurityPolicyTlv_throwsIllegalArgument() {
        byte[] invalidTlv = removeTlv(VALID_DATASET, TYPE_SECURITY_POLICY);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(invalidTlv));
    }

    @Test
    public void fromThreadTlvs_invalidTlv_lengthAndDataMissing_throwsIllegalArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(new byte[] {(byte) 0x00}));
    }

    @Test
    public void fromThreadTlvs_invalidTlv_prematureEndOfData_throwsIllegalArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveOperationalDataset.fromThreadTlvs(new byte[] {0x00, 0x03, 0x00, 0x00}));
    }

    @Test
    public void fromThreadTlvs_validFullDataset_success() {
        // A valid Thread active operational dataset:
        // Active Timestamp: 1
        // Channel: 19
        // Channel Mask: 0x07FFF800
        // Ext PAN ID: ACC214689BC40BDF
        // Mesh Local Prefix: fd64:db12:25f4:7e0b::/64
        // Network Key: F26B3153760F519A63BAFDDFFC80D2AF
        // Network Name: OpenThread-d9a0
        // PAN ID: 0xD9A0
        // PSKc: A245479C836D551B9CA557F7B9D351B4
        // Security Policy: 672 onrcb
        byte[] validDatasetTlv =
                base16().decode(
                                "0E080000000000010000000300001335060004001FFFE002"
                                        + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                        + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                        + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                        + "B9D351B40C0402A0FFF8");

        ActiveOperationalDataset dataset = ActiveOperationalDataset.fromThreadTlvs(validDatasetTlv);

        assertThat(dataset.getNetworkKey())
                .isEqualTo(base16().decode("F26B3153760F519A63BAFDDFFC80D2AF"));
        assertThat(dataset.getPanId()).isEqualTo(0xd9a0);
        assertThat(dataset.getExtendedPanId()).isEqualTo(base16().decode("ACC214689BC40BDF"));
        assertThat(dataset.getChannel()).isEqualTo(19);
        assertThat(dataset.getNetworkName()).isEqualTo("OpenThread-d9a0");
        assertThat(dataset.getPskc())
                .isEqualTo(base16().decode("A245479C836D551B9CA557F7B9D351B4"));
        assertThat(dataset.getActiveTimestamp())
                .isEqualTo(new OperationalDatasetTimestamp(1, 0, false));
        SparseArray<byte[]> channelMask = dataset.getChannelMask();
        assertThat(channelMask.size()).isEqualTo(1);
        assertThat(channelMask.get(CHANNEL_PAGE_2P4GHZ))
                .isEqualTo(new byte[] {0x00, 0x1f, (byte) 0xff, (byte) 0xe0});
        assertThat(dataset.getMeshLocalPrefix())
                .isEqualTo(new IpPrefix("fd64:db12:25f4:7e0b::/64"));
        assertThat(dataset.getSecurityPolicy())
                .isEqualTo(new SecurityPolicy(672, new byte[] {(byte) 0xff, (byte) 0xf8}));
    }

    @Test
    public void fromThreadTlvs_containsUnknownTlvs_unknownTlvsRetained() {
        byte[] datasetWithUnknownTlvs = addTlv(VALID_DATASET, "AA01FFBB020102");

        ActiveOperationalDataset dataset1 =
                ActiveOperationalDataset.fromThreadTlvs(datasetWithUnknownTlvs);
        ActiveOperationalDataset dataset2 =
                ActiveOperationalDataset.fromThreadTlvs(dataset1.toThreadTlvs());

        SparseArray<byte[]> unknownTlvs = dataset1.getUnknownTlvs();
        assertThat(unknownTlvs.size()).isEqualTo(2);
        assertThat(unknownTlvs.get(0xAA)).isEqualTo(new byte[] {(byte) 0xFF});
        assertThat(unknownTlvs.get(0xBB)).isEqualTo(new byte[] {0x01, 0x02});
        assertThat(dataset2).isEqualTo(dataset1);
    }

    @Test
    public void toThreadTlvs_conversionIsLossLess() {
        ActiveOperationalDataset dataset1 = ActiveOperationalDataset.createRandomDataset();

        ActiveOperationalDataset dataset2 =
                ActiveOperationalDataset.fromThreadTlvs(dataset1.toThreadTlvs());

        assertThat(dataset2).isEqualTo(dataset1);
    }

    @Test
    public void createRandomDataset_fieldsAreRandomized() {
        // Always return the max bounded value
        doAnswer(
                        invocation -> {
                            Integer bound = invocation.getArgument(0);
                            return bound - 1;
                        })
                .when(mockRandom)
                .nextInt(anyInt());
        doAnswer(
                        invocation -> {
                            byte[] output = invocation.getArgument(0);
                            for (int i = 0; i < output.length; ++i) {
                                output[i] = (byte) (i + 10);
                            }
                            return null;
                        })
                .when(mockRandom)
                .nextBytes(any(byte[].class));
        doAnswer(
                        invocation -> {
                            byte[] output = invocation.getArgument(0);
                            for (int i = 0; i < output.length; ++i) {
                                output[i] = (byte) (i + 30);
                            }
                            return null;
                        })
                .when(mockSecureRandom)
                .nextBytes(any(byte[].class));

        ActiveOperationalDataset dataset =
                ActiveOperationalDataset.createRandomDataset(mockRandom, mockSecureRandom);

        assertThat(dataset.getActiveTimestamp())
                .isEqualTo(new OperationalDatasetTimestamp(1, 0, false));
        assertThat(dataset.getExtendedPanId())
                .isEqualTo(new byte[] {10, 11, 12, 13, 14, 15, 16, 17});
        assertThat(dataset.getMeshLocalPrefix())
                .isEqualTo(new IpPrefix("fd0b:0c0d:0e0f:1011::/64"));
        verify(mockRandom, times(2)).nextBytes(any(byte[].class));
        assertThat(dataset.getPanId()).isEqualTo(0xfffe); // PAN ID <= 0xfffe
        verify(mockRandom, times(1)).nextInt(eq(0xffff));
        assertThat(dataset.getChannel()).isEqualTo(26);
        verify(mockRandom, times(1)).nextInt(eq(16));
        assertThat(dataset.getChannelPage()).isEqualTo(0);
        assertThat(dataset.getChannelMask().size()).isEqualTo(1);
        assertThat(dataset.getPskc())
                .isEqualTo(
                        new byte[] {
                            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45
                        });
        assertThat(dataset.getNetworkKey())
                .isEqualTo(
                        new byte[] {
                            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45
                        });
        verify(mockSecureRandom, times(2)).nextBytes(any(byte[].class));
        assertThat(dataset.getSecurityPolicy())
                .isEqualTo(new SecurityPolicy(672, new byte[] {(byte) 0xff, (byte) 0xf8}));
    }

    @Test
    public void builder_buildWithdefaultValues_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> new Builder().build());
    }

    @Test
    public void builder_buildWithTooLongTlvs_throwsIllegalState() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());
        for (int i = 0; i < 10; i++) {
            builder.addUnknownTlv(i, new byte[20]);
        }

        assertThrows(IllegalStateException.class, () -> new Builder().build());
    }

    @Test
    public void builder_setValidNetworkKey_success() {
        final byte[] networkKey =
                new byte[] {
                    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
                    0x0d, 0x0e, 0x0f
                };

        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setNetworkKey(networkKey)
                        .build();

        assertThat(dataset.getNetworkKey()).isEqualTo(networkKey);
    }

    @Test
    public void builder_setInvalidNetworkKey_throwsIllegalArgument() {
        byte[] invalidNetworkKey = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(
                IllegalArgumentException.class, () -> builder.setNetworkKey(invalidNetworkKey));
    }

    @Test
    public void builder_setValidExtendedPanId_success() {
        byte[] extendedPanId = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};

        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setExtendedPanId(extendedPanId)
                        .build();

        assertThat(dataset.getExtendedPanId()).isEqualTo(extendedPanId);
    }

    @Test
    public void builder_setInvalidExtendedPanId_throwsIllegalArgument() {
        byte[] extendedPanId = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(IllegalArgumentException.class, () -> builder.setExtendedPanId(extendedPanId));
    }

    @Test
    public void builder_setValidPanId_success() {
        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setPanId(0xfffe)
                        .build();

        assertThat(dataset.getPanId()).isEqualTo(0xfffe);
    }

    @Test
    public void builder_setInvalidPanId_throwsIllegalArgument() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(IllegalArgumentException.class, () -> builder.setPanId(0xffff));
    }

    @Test
    public void builder_setInvalidChannel_throwsIllegalArgument() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(IllegalArgumentException.class, () -> builder.setChannel(0, 0));
        assertThrows(IllegalArgumentException.class, () -> builder.setChannel(0, 27));
    }

    @Test
    public void builder_setValid2P4GhzChannel_success() {
        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setChannel(CHANNEL_PAGE_2P4GHZ, 16)
                        .build();

        assertThat(dataset.getChannel()).isEqualTo(16);
        assertThat(dataset.getChannelPage()).isEqualTo(CHANNEL_PAGE_2P4GHZ);
    }

    @Test
    public void builder_setValidNetworkName_success() {
        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setNetworkName("ot-network")
                        .build();

        assertThat(dataset.getNetworkName()).isEqualTo("ot-network");
    }

    @Test
    public void builder_setEmptyNetworkName_throwsIllegalArgument() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(IllegalArgumentException.class, () -> builder.setNetworkName(""));
    }

    @Test
    public void builder_setTooLongNetworkName_throwsIllegalArgument() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(
                IllegalArgumentException.class, () -> builder.setNetworkName("openthread-network"));
    }

    @Test
    public void builder_setTooLongUtf8NetworkName_throwsIllegalArgument() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        // UTF-8 encoded length of "我的线程网络" is 18 bytes which exceeds the max length
        assertThrows(IllegalArgumentException.class, () -> builder.setNetworkName("我的线程网络"));
    }

    @Test
    public void builder_setValidUtf8NetworkName_success() {
        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setNetworkName("我的网络")
                        .build();

        assertThat(dataset.getNetworkName()).isEqualTo("我的网络");
    }

    @Test
    public void builder_setValidPskc_success() {
        byte[] pskc = base16().decode("A245479C836D551B9CA557F7B9D351B4");

        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset()).setPskc(pskc).build();

        assertThat(dataset.getPskc()).isEqualTo(pskc);
    }

    @Test
    public void builder_setTooLongPskc_throwsIllegalArgument() {
        byte[] tooLongPskc = base16().decode("A245479C836D551B9CA557F7B9D351B400");
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(IllegalArgumentException.class, () -> builder.setPskc(tooLongPskc));
    }

    @Test
    public void builder_setValidChannelMask_success() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());
        SparseArray<byte[]> channelMask = new SparseArray<byte[]>(1);
        channelMask.put(0, new byte[] {0x00, 0x00, 0x01, 0x00});

        ActiveOperationalDataset dataset = builder.setChannelMask(channelMask).build();

        SparseArray<byte[]> resultChannelMask = dataset.getChannelMask();
        assertThat(resultChannelMask.size()).isEqualTo(1);
        assertThat(resultChannelMask.get(0)).isEqualTo(new byte[] {0x00, 0x00, 0x01, 0x00});
    }

    @Test
    public void builder_setEmptyChannelMask_throwsIllegalArgument() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(
                IllegalArgumentException.class,
                () -> builder.setChannelMask(new SparseArray<byte[]>()));
    }

    @Test
    public void builder_setValidActiveTimestamp_success() {
        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setActiveTimestamp(
                                new OperationalDatasetTimestamp(
                                        /* seconds= */ 1,
                                        /* ticks= */ 0,
                                        /* isAuthoritativeSource= */ true))
                        .build();

        assertThat(dataset.getActiveTimestamp().getSeconds()).isEqualTo(1);
        assertThat(dataset.getActiveTimestamp().getTicks()).isEqualTo(0);
        assertThat(dataset.getActiveTimestamp().isAuthoritativeSource()).isTrue();
    }

    @Test
    public void builder_setInvalidMeshLocalPrefix_throwsIllegalArguments() {
        Builder builder = new Builder(ActiveOperationalDataset.createRandomDataset());

        assertThrows(
                IllegalArgumentException.class,
                () -> builder.setMeshLocalPrefix(new IpPrefix("fd00::/32")));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.setMeshLocalPrefix(new IpPrefix("fd00::/96")));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.setMeshLocalPrefix(new IpPrefix("fc00::/64")));
    }

    @Test
    public void builder_setValidMeshLocalPrefix_success() {
        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setMeshLocalPrefix(new IpPrefix("fd00::/64"))
                        .build();

        assertThat(dataset.getMeshLocalPrefix()).isEqualTo(new IpPrefix("fd00::/64"));
    }

    @Test
    public void builder_setValid1P2SecurityPolicy_success() {
        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setSecurityPolicy(
                                new SecurityPolicy(672, new byte[] {(byte) 0xff, (byte) 0xf8}))
                        .build();

        assertThat(dataset.getSecurityPolicy().getRotationTimeHours()).isEqualTo(672);
        assertThat(dataset.getSecurityPolicy().getFlags())
                .isEqualTo(new byte[] {(byte) 0xff, (byte) 0xf8});
    }

    @Test
    public void builder_setValid1P1SecurityPolicy_success() {
        ActiveOperationalDataset dataset =
                new Builder(ActiveOperationalDataset.createRandomDataset())
                        .setSecurityPolicy(new SecurityPolicy(672, new byte[] {(byte) 0xff}))
                        .build();

        assertThat(dataset.getSecurityPolicy().getRotationTimeHours()).isEqualTo(672);
        assertThat(dataset.getSecurityPolicy().getFlags()).isEqualTo(new byte[] {(byte) 0xff});
    }

    @Test
    public void builder_setUnknownTlvs_success() {
        ActiveOperationalDataset dataset1 = ActiveOperationalDataset.fromThreadTlvs(VALID_DATASET);
        SparseArray<byte[]> unknownTlvs = new SparseArray<>(2);
        unknownTlvs.put(0x33, new byte[] {1, 2, 3});
        unknownTlvs.put(0x44, new byte[] {1, 2, 3, 4});

        ActiveOperationalDataset dataset2 =
                new ActiveOperationalDataset.Builder(dataset1).setUnknownTlvs(unknownTlvs).build();

        assertThat(dataset1.getUnknownTlvs().size()).isEqualTo(0);
        assertThat(dataset2.getUnknownTlvs().size()).isEqualTo(2);
        assertThat(dataset2.getUnknownTlvs().get(0x33)).isEqualTo(new byte[] {1, 2, 3});
        assertThat(dataset2.getUnknownTlvs().get(0x44)).isEqualTo(new byte[] {1, 2, 3, 4});
    }

    @Test
    public void securityPolicy_fromTooShortTlvValue_throwsIllegalArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SecurityPolicy.fromTlvValue(new byte[] {0x01}));
        assertThrows(
                IllegalArgumentException.class,
                () -> SecurityPolicy.fromTlvValue(new byte[] {0x01, 0x02}));
    }

    @Test
    public void securityPolicy_toTlvValue_conversionIsLossLess() {
        SecurityPolicy policy1 = new SecurityPolicy(672, new byte[] {(byte) 0xFF, (byte) 0xF8});

        SecurityPolicy policy2 = SecurityPolicy.fromTlvValue(policy1.toTlvValue());

        assertThat(policy2).isEqualTo(policy1);
    }

    @Test
    public void securityPolicy_invalidRotationTime_throwsIllegalArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SecurityPolicy(0, new byte[] {(byte) 0xff, (byte) 0xf8}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SecurityPolicy(0x1ffff, new byte[] {(byte) 0xff, (byte) 0xf8}));
    }

    @Test
    public void securityPolicy_emptyFlags_throwsIllegalArguments() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy(672, new byte[] {}));
    }

    @Test
    public void securityPolicy_tooLongFlags_success() {
        SecurityPolicy securityPolicy =
                new SecurityPolicy(672, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});

        assertThat(securityPolicy.getFlags()).isEqualTo(new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
    }

    @Test
    public void securityPolicy_equals() {
        new EqualsTester()
                .addEqualityGroup(
                        new SecurityPolicy(672, new byte[] {(byte) 0xff, (byte) 0xf8}),
                        new SecurityPolicy(672, new byte[] {(byte) 0xff, (byte) 0xf8}))
                .addEqualityGroup(
                        new SecurityPolicy(1, new byte[] {(byte) 0xff}),
                        new SecurityPolicy(1, new byte[] {(byte) 0xff}))
                .addEqualityGroup(
                        new SecurityPolicy(1, new byte[] {(byte) 0xff, (byte) 0xf8}),
                        new SecurityPolicy(1, new byte[] {(byte) 0xff, (byte) 0xf8}))
                .testEquals();
    }
}
