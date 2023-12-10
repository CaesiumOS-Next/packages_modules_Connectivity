package com.android.net.module.util.structs;

import static org.junit.Assert.assertEquals;
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
public class StructMif6ctlTest {
    @Test
    public void testConstructor() {
        StructMif6ctl mif6ctl = new StructMif6ctl(10, (short) 11,
                (short) 12, 13, 14L);

        assertEquals(10, mif6ctl.mif6cMifi);
        assertEquals(11, mif6ctl.mif6cFlags);
        assertEquals(12, mif6ctl.vifcThreshold);
        assertEquals(13, mif6ctl.mif6cPifi);
        assertEquals(14, mif6ctl.vifcRateLimit);
    }
}
