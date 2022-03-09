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

package android.net;

import static android.net.QosCallbackException.EX_TYPE_FILTER_NONE;
import static android.net.QosCallbackException.EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED;
import static android.net.QosCallbackException.EX_TYPE_FILTER_SOCKET_NOT_BOUND;
import static android.net.QosCallbackException.EX_TYPE_FILTER_SOCKET_REMOTE_ADDRESS_CHANGED;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class QosSocketFilterTest {
    @Mock
    private Network mMockNetwork;
    @Test
    public void testPortExactMatch() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.4");
        assertTrue(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 10), addressB, 10, 10));

    }

    @Test
    public void testPortLessThanStart() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.4");
        assertFalse(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 8), addressB, 10, 10));
    }

    @Test
    public void testPortGreaterThanEnd() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.4");
        assertFalse(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 18), addressB, 10, 10));
    }

    @Test
    public void testPortBetweenStartAndEnd() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.4");
        assertTrue(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 10), addressB, 8, 18));
    }

    @Test
    public void testAddressesDontMatch() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.5");
        assertFalse(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 10), addressB, 10, 10));
    }

    @Test
    public void testAddressMatchWithAnyAddresses() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("0.0.0.0");
        assertTrue(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 10), addressB, 10, 10));
    }

    @Test
    public void testProtocolIdMatch() throws Exception {
        DatagramSocket socket;
        socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 7757));
        socket.connect(new InetSocketAddress("127.0.0.1", 8750));
        QosSocketInfo socketInfo = new QosSocketInfo(mMockNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.matchesProtocolId(IPPROTO_UDP));
        assertFalse(socketFilter.matchesProtocolId(IPPROTO_TCP));
    }

    @Test
    public void testValidate() throws Exception {
        DatagramSocket socket;
        socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 7757));
        socket.connect(new InetSocketAddress("127.0.0.1", 8750));
        QosSocketInfo socketInfo = new QosSocketInfo(mMockNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_NONE);
    }

    @Test
    public void testValidateUnbind() throws Exception {
        DatagramSocket socket;
        socket = new DatagramSocket(null);
        QosSocketInfo socketInfo = new QosSocketInfo(mMockNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_SOCKET_NOT_BOUND);
    }

    @Test
    public void testValidateLocalAddressChanged() throws Exception {
        DatagramSocket socket;
        socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 7757));
        socket.connect(new InetSocketAddress("127.0.0.1", 8750));
        QosSocketInfo socketInfo = new QosSocketInfo(mMockNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_NONE);
        socket.bind(new InetSocketAddress("127.0.0.1", 7767));
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED);
    }

    @Test
    public void testValidateRemoteAddressChanged() throws Exception {
        DatagramSocket socket;
        socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 7757));
        socket.connect(new InetSocketAddress("127.0.0.1", 8750));
        QosSocketInfo socketInfo = new QosSocketInfo(mMockNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_NONE);
        socket.disconnect();
        socket.connect(new InetSocketAddress("127.0.0.1", 8770));
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_SOCKET_REMOTE_ADDRESS_CHANGED);
    }
}

