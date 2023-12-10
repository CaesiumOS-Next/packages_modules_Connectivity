package com.android.net.module.util.structs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.util.ArraySet;
import androidx.test.runner.AndroidJUnit4;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StructMf6cctlTest {
    @Test
    public void testConstructor() throws UnknownHostException {
        final Inet6Address source = (Inet6Address) InetAddress.getByName("fe80::2222");
        final Inet6Address destination = (Inet6Address) InetAddress.getByName("ff05::5555");
        final Set<Integer> oifset = new ArraySet<>();
        oifset.add(10);
        final byte[] oifsetBytes = new byte[32];
        oifsetBytes[1] = 4;

        StructMf6cctl mf6cctl = new StructMf6cctl(source, destination, 1, oifset);

        assertTrue(Arrays.equals(source.getAddress(), mf6cctl.originAddress));
        assertTrue(Arrays.equals(destination.getAddress(), mf6cctl.groupAddress));
        assertEquals(1, mf6cctl.mf6ccParent);
        assertTrue(Arrays.equals(oifsetBytes, mf6cctl.mf6ccIfset));
    }

    @Test
    public void testConstructor_tooBigOifIndex_throwsExeption() throws UnknownHostException {
        final Inet6Address source = (Inet6Address) InetAddress.getByName("fe80::2222");
        final Inet6Address destination = (Inet6Address) InetAddress.getByName("ff05::5555");
        final Set<Integer> oifset = new ArraySet<>();
        oifset.add(1000);

        assertThrows(IllegalArgumentException.class,
            () -> new StructMf6cctl(source, destination, 1, oifset));
    }
}
