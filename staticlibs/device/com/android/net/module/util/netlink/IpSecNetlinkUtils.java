package com.android.net.module.util.netlink;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.IPPROTO_ESP;
import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.NETLINK_XFRM;
import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.XFRM_MSG_NEWSA;

import android.system.ErrnoException;

import androidx.annotation.NonNull;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;

public class IpSecNetlinkUtils {
    private static final int TIMEOUT_MS = 500;
    public static final int DEFAULT_RECV_BUFSIZE = 8 * 1024;

    public IpSecXfrmNetlinkNewSaMessage ipSecGetSa(@NonNull InetAddress destAddress, long spi)
            throws ErrnoException, SocketException, InterruptedIOException {

        final int family = destAddress instanceof Inet4Address ? AF_INET : AF_INET6;

        final byte[] req =
                IpSecXfrmNetlinkGetSaMessage.newIpSecXfrmNetlinkGetSaMessage(
                        destAddress, spi, family, (short) IPPROTO_ESP);

        FileDescriptor fd = null;
        try {
            fd = NetlinkUtils.netlinkSocketForProto(NETLINK_XFRM);
            NetlinkUtils.connectToKernel(fd);

            NetlinkUtils.sendMessage(fd, req, 0, req.length, TIMEOUT_MS);

            ByteBuffer response = NetlinkUtils.recvMessage(fd, DEFAULT_RECV_BUFSIZE, TIMEOUT_MS);
            while (response.remaining() > 0) {
                final NetlinkMessage msg = IpSecXfrmNetlinkMessage.parse(response, NETLINK_XFRM);
                final StructNlMsgHdr hdr = msg.getHeader();

                if (hdr.nlmsg_type == XFRM_MSG_NEWSA) {
                    return (IpSecXfrmNetlinkNewSaMessage) msg;
                }
                android.util.Log.i("TEST", "hdr.nlmsg_type: " + hdr.nlmsg_type);
            }
            return null;
        } finally {
            if (fd != null) {
                IoUtils.closeQuietly(fd);
            }
        }
    }
}
