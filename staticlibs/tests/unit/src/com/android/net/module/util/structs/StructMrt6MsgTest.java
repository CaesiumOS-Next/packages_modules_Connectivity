package com.android.net.module.util.structs;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;
import com.android.net.module.util.Struct;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StructMrt6MsgTest {
    @Test
    public void testParseMrt6Msg() throws UnknownHostException {
        byte[] msgbytes = new byte[] {
            0, /* mbz = 0 */
            1, /* message type = MRT6MSG_NOCACHE */
            1, 0, /* mif u16 = 1 */
            0, 0, 0, 0, /* padding */
            -3, 48, 96, -103, 69, 57, 0, 1, -75, -53, -97, 83, -115, -50, -58, 85, /* source */
            -1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, 52, /* destination */
        };
        final ByteBuffer buf = ByteBuffer.wrap(msgbytes);
        StructMrt6Msg mrt6Msg = StructMrt6Msg.parse(buf);
        final Inet6Address source =
                (Inet6Address) InetAddress.getByName("fd30:6099:4539:1:b5cb:9f53:8dce:c655");
        final Inet6Address group = (Inet6Address) InetAddress.getByName("ff05::1234");

        assertEquals(1, mrt6Msg.mif);
        assertEquals(StructMrt6Msg.MRT6MSG_NOCACHE, mrt6Msg.msgType);
        assertEquals(source, mrt6Msg.src);
        assertEquals(group, mrt6Msg.dst);
    }
}
