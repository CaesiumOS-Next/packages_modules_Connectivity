package com.android.net.module.util.netlink;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_ESP;
import static android.system.OsConstants.NETLINK_XFRM;

import android.system.ErrnoException;

import androidx.annotation.NonNull;

import com.android.net.module.util.HexDump;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;

public class IpSecNetlinkUtils {
    private static final int TIMEOUT_MS = 500;
    public static final int DEFAULT_RECV_BUFSIZE = 8 * 1024;

    public static void ipSecDumpSa(int seqNo, @NonNull InetAddress destAddress, long spi)
            throws ErrnoException, SocketException, InterruptedIOException {
        final FileDescriptor fd = NetlinkUtils.netlinkSocketForProto(NETLINK_XFRM);
        NetlinkUtils.connectSocketToNetlink(fd);

        final int family = destAddress instanceof Inet4Address ? AF_INET : AF_INET6;

        final byte[] req =
                IpSecXfrmNetlinkGetSaMessage.newIpSecXfrmNetlinkGetSaMessage(
                        seqNo, destAddress, spi, family, (short) IPPROTO_ESP);

        NetlinkUtils.sendMessage(fd, req, 0, req.length, TIMEOUT_MS);

        int responseMessageCount = 0;
        int doneMessageCount = 0;

        try {
            while (doneMessageCount == 0) {
                ByteBuffer response =
                        NetlinkUtils.recvMessage(fd, DEFAULT_RECV_BUFSIZE, TIMEOUT_MS);
                android.util.Log.i(
                        "TEST", "recvMessage " + HexDump.dumpHexString(response.array()));
                android.util.Log.i("TEST", "response.remaining() " + response.remaining());
                while (response.remaining() > 0) {
                    final NetlinkMessage msg = IpSecXfrmNetlinkNewSaMessage.parse(response);
                    final StructNlMsgHdr hdr = msg.getHeader();
                    android.util.Log.i("TEST", "hdr.nlmsg_type: " + hdr.nlmsg_type);
                    android.util.Log.i("TEST", "msg: " + msg);

                    if (hdr.nlmsg_type == NetlinkConstants.NLMSG_DONE) {
                        doneMessageCount++;
                        continue;
                    }
                    responseMessageCount++;
                }
            }
        } catch (Exception e) {
            android.util.Log.i("TEST", "exception " + e);
        }
    }
}
