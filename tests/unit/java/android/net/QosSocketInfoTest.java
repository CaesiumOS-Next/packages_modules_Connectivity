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

package android.net;

import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_STREAM;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class QosSocketInfoTest {
    @Mock
    private Network mMockNetwork = mock(Network.class);

    @Test
    public void testConstructWithSock() throws Exception {
        Socket socket;
        ServerSocket server = new ServerSocket();

        InetSocketAddress localAddr = new InetSocketAddress("127.0.0.1", 57753);
        InetSocketAddress remoteAddr = new InetSocketAddress("127.0.0.1", 58753);
        server.bind(remoteAddr);
        Thread serverThread = new Thread(()-> {
            boolean connected = false;
            while (!connected) {
                try {
                    Socket sock = server.accept();
                    connected = true;
                } catch (Exception e) {
                }
            }
        });
        serverThread.start();
        socket = new Socket(remoteAddr.getAddress(), remoteAddr.getPort(),
                localAddr.getAddress(), localAddr.getPort());
        QosSocketInfo sockInfo = new QosSocketInfo(mMockNetwork, socket);
        assertTrue(sockInfo.getLocalSocketAddress().equals(localAddr));
        assertTrue(sockInfo.getRemoteSocketAddress().equals(remoteAddr));
        assertTrue(sockInfo.getSocketType() == SOCK_STREAM);
        socket.close();
        server.close();
    }

    @Test
    public void testConstructWithDatagramSock() throws Exception {
        DatagramSocket socket;
        InetSocketAddress localAddr = new InetSocketAddress("127.0.0.1", 27753);
        InetSocketAddress remoteAddr = new InetSocketAddress("127.0.0.1", 28753);
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(localAddr);
        socket.connect(remoteAddr);
        QosSocketInfo sockInfo = new QosSocketInfo(mMockNetwork, socket);
        assertTrue(sockInfo.getLocalSocketAddress().equals(localAddr));
        assertTrue(sockInfo.getRemoteSocketAddress().equals(remoteAddr));
        assertTrue(sockInfo.getSocketType() == SOCK_DGRAM);
        socket.close();
    }
}
