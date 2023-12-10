package com.android.net.module.util.structs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;
import com.android.net.module.util.Struct;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StructMrt6MsgTest {

    final byte[] msgbytes = new byte[] {
        0, /* mbz = 0 */
        1, /* message type = MRT6MSG_NOCACHE */
        1, 0, /* mif u16 = 1 */
        0, 0, 0, 0, /* padding */
        32, 1, 13, -72, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, /* source */
        -1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, 52, /* destination */
    };

    @Test
    public void testParseMrt6Msg() throws UnknownHostException {
        final Inet6Address source = (Inet6Address) InetAddress.getByName("2001:db8::1");
        final Inet6Address group = (Inet6Address) InetAddress.getByName("ff05::1234");

        final ByteBuffer buf = ByteBuffer.wrap(msgbytes);
        StructMrt6Msg mrt6Msg = StructMrt6Msg.parse(buf);

        assertEquals(1, mrt6Msg.mif);
        assertEquals(StructMrt6Msg.MRT6MSG_NOCACHE, mrt6Msg.msgType);
        assertEquals(source, mrt6Msg.src);
        assertEquals(group, mrt6Msg.dst);
    }

    @Test
    public void testWriteToBytes() throws UnknownHostException {
        final Inet6Address source = (Inet6Address) InetAddress.getByName("2001:db8::1");
        final Inet6Address group = (Inet6Address) InetAddress.getByName("ff05::1234");

        StructMrt6Msg msg = new StructMrt6Msg((byte) 0 /* mbz must be 0 */,
                StructMrt6Msg.MRT6MSG_NOCACHE,
                1 /* mif */,
                source,
                group);
        byte[] bytes = msg.writeToBytes();

        assertTrue(Arrays.equals(msgbytes, bytes));
    }
}
