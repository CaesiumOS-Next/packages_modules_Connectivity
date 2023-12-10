package com.android.net.module.util.structs;

import static android.system.OsConstants.AF_INET6;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.util.ArraySet;
import androidx.test.runner.AndroidJUnit4;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StructMf6cctlTest {
    final byte[] mf6cctlBytes = new byte[] {
        10, 0, /* AF_INET6 */
        0, 0, /* originPort */
        0, 0, 0, 0, /* originFlowinfo */
        32, 1, 13, -72, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, /* originAddress */
        0, 0, 0, 0, /* originScopeId */
        10, 0, /* AF_INET6 */
        0, 0, /* groupPort */
        0, 0, 0, 0, /* groupFlowinfo*/
        -1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, 52, /*groupAddress*/
        0, 0, 0, 0, /* groupScopeId*/
        1, 0, /* mf6ccParent */
        0, 0, /* padding */
        0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 /* mf6ccIfset */
    };

    final int oif = 10;
    final byte[] oifsetBytes = new byte[] {
        0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    @Test
    public void testConstructor() throws UnknownHostException {
        final Inet6Address source = (Inet6Address) InetAddress.getByName("2001:db8::1");
        final Inet6Address destination = (Inet6Address) InetAddress.getByName("ff05::1234");
        final Set<Integer> oifset = new ArraySet<>();
        oifset.add(oif);

        StructMf6cctl mf6cctl = new StructMf6cctl(source, destination,
                1 /* mf6ccParent */, oifset);

        assertTrue(Arrays.equals(source.getAddress(), mf6cctl.originAddress));
        assertTrue(Arrays.equals(destination.getAddress(), mf6cctl.groupAddress));
        assertEquals(1, mf6cctl.mf6ccParent);
        assertTrue(Arrays.equals(oifsetBytes, mf6cctl.mf6ccIfset));
    }

    @Test
    public void testConstructor_tooBigOifIndex_throwsIllegalArgumentException()
            throws UnknownHostException {
        final Inet6Address source = (Inet6Address) InetAddress.getByName("2001:db8::1");
        final Inet6Address destination = (Inet6Address) InetAddress.getByName("ff05::1234");
        final Set<Integer> oifset = new ArraySet<>();
        oifset.add(1000);

        assertThrows(IllegalArgumentException.class,
            () -> new StructMf6cctl(source, destination, 1, oifset));
    }

    @Test
    public void testParseMf6cctl() throws UnknownHostException {
        final Inet6Address source = (Inet6Address) InetAddress.getByName("2001:db8::1");
        final Inet6Address destination = (Inet6Address) InetAddress.getByName("ff05::1234");

        final ByteBuffer buf = ByteBuffer.wrap(mf6cctlBytes);
        buf.order(ByteOrder.nativeOrder());
        StructMf6cctl mf6cctl = StructMf6cctl.parse(StructMf6cctl.class, buf);

        assertEquals(AF_INET6, mf6cctl.originFamily);
        assertEquals(AF_INET6, mf6cctl.groupFamily);
        assertTrue(Arrays.equals(source.getAddress(), mf6cctl.originAddress));
        assertTrue(Arrays.equals(destination.getAddress(), mf6cctl.groupAddress));
        assertEquals(1, mf6cctl.mf6ccParent);
        assertTrue("mf6ccIfset = " + Arrays.toString(mf6cctl.mf6ccIfset),
                Arrays.equals(oifsetBytes, mf6cctl.mf6ccIfset));
    }

    @Test
    public void testWriteToBytes() throws UnknownHostException {
        final Inet6Address source = (Inet6Address) InetAddress.getByName("2001:db8::1");
        final Inet6Address destination = (Inet6Address) InetAddress.getByName("ff05::1234");
        final Set<Integer> oifset = new ArraySet<>();
        oifset.add(oif);

        StructMf6cctl mf6cctl = new StructMf6cctl(source, destination,
                1 /* mf6ccParent */, oifset);
        byte[] bytes = mf6cctl.writeToBytes();

        assertTrue("bytes = " + Arrays.toString(bytes), Arrays.equals(mf6cctlBytes, bytes));
    }
}
