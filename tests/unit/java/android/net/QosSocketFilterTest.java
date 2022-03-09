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

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class QosSocketFilterTest {
    private static final int TEST_NET_ID = 1777;
    private final Network mNetwork = new Network(TEST_NET_ID);
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
    public void testAddressMatchWithAnyLocalAddresses() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("0.0.0.0");
        assertTrue(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 10), addressB, 10, 10));
    }

    @Test
    public void testProtocolMatch() throws Exception {
        DatagramSocket socket;
        socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 31757));
        socket.connect(new InetSocketAddress("127.0.0.1", 33750));
        QosSocketInfo socketInfo = new QosSocketInfo(mNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.matchesProtocol(IPPROTO_UDP));
        assertFalse(socketFilter.matchesProtocol(IPPROTO_TCP));
        socket.close();
    }

    @Test
    public void testValidate() throws Exception {
        DatagramSocket socket;
        socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 23757));
        socket.connect(new InetSocketAddress("127.0.0.1", 27750));
        QosSocketInfo socketInfo = new QosSocketInfo(mNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_NONE);
        socket.close();
    }

    @Test
    public void testValidateUnbind() throws Exception {
        if (Build.VERSION.DEVICE_INITIAL_SDK_INT <= Build.VERSION_CODES.S) {
            return;
        }
        DatagramSocket socket;
        socket = new DatagramSocket(null);
        QosSocketInfo socketInfo = new QosSocketInfo(mNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_SOCKET_NOT_BOUND);
        socket.close();
    }

    @Test
    public void testValidateLocalAddressChanged() throws Exception {
        DatagramSocket socket;
        socket = new DatagramSocket(null);
        QosSocketInfo socketInfo = new QosSocketInfo(mNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        socket.bind(new InetSocketAddress("127.0.0.1", 15357));
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED);
        socket.close();
    }

    @Test
    public void testValidateRemoteAddressChanged() throws Exception {
        if (Build.VERSION.DEVICE_INITIAL_SDK_INT <= Build.VERSION_CODES.S) {
            return;
        }
        DatagramSocket socket;
        socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 7758));
        socket.connect(new InetSocketAddress("127.0.0.1", 8751));
        QosSocketInfo socketInfo = new QosSocketInfo(mNetwork, socket);
        QosSocketFilter socketFilter = new QosSocketFilter(socketInfo);
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_NONE);
        socket.disconnect();
        socket.connect(new InetSocketAddress("127.0.0.1", 8770));
        assertTrue(socketFilter.validate() == EX_TYPE_FILTER_SOCKET_REMOTE_ADDRESS_CHANGED);
        socket.close();
    }
}

