package com.android.net.module.util.netlink;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_ESP;
import static android.system.OsConstants.NETLINK_XFRM;

import androidx.annotation.NonNull;

import com.android.net.module.util.HexDump;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;

public class IpSecNetlinkUtils {
    private static final int TIMEOUT_MS = 500;
    public static final int DEFAULT_RECV_BUFSIZE = 8 * 1024;
    public final FileDescriptor fd;

    public IpSecNetlinkUtils() {
        try {
            android.util.Log.i("TEST", "Open socket");
            fd = NetlinkUtils.netlinkSocketForProto(NETLINK_XFRM);
            NetlinkUtils.connectSocketToNetlink(fd);
            android.util.Log.i("TEST", "Open socket done");
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public void close() {
        IoUtils.closeQuietly(fd);
    }

    public void ipSecDumpSa(int seqNo, @NonNull InetAddress destAddress, long spi) {

        final int family = destAddress instanceof Inet4Address ? AF_INET : AF_INET6;

        final byte[] req =
                IpSecXfrmNetlinkGetSaMessage.newIpSecXfrmNetlinkGetSaMessage(
                        seqNo, destAddress, spi, family, (short) IPPROTO_ESP);

        try {
            NetlinkUtils.sendMessage(fd, req, 0, req.length, TIMEOUT_MS);

            ByteBuffer response = NetlinkUtils.recvMessage(fd, DEFAULT_RECV_BUFSIZE, TIMEOUT_MS);
            // android.util.Log.i("TEST", "recvMessage " + HexDump.dumpHexString(response.array()));
            // android.util.Log.i("TEST", "response.remaining() " + response.remaining());
            while (response.remaining() > 0) {
                final NetlinkMessage msg = IpSecXfrmNetlinkNewSaMessage.parse(response);
                final StructNlMsgHdr hdr = msg.getHeader();
                android.util.Log.i("TEST", "hdr.nlmsg_type: " + hdr.nlmsg_type);
                // android.util.Log.i("TEST", "msg: " + msg);
            }
        } catch (Exception e) {
            android.util.Log.i("TEST", "ipSecDumpSa exception " + e);
            throw new IllegalArgumentException(e);
        }
    }
}
