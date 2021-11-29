/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.os.UserHandle.MIN_SECONDARY_USER_ID;
import static android.os.UserHandle.SYSTEM;
import static android.os.UserHandle.USER_SYSTEM;
import static android.os.UserHandle.getUid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.os.UserHandle;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UidRangeTest {

    /*
     * UidRange is no longer passed to netd. UID ranges between the framework and netd are passed as
     * UidRangeParcel objects.
     */

    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    @Test
    public void testSingleItemUidRangeAllowed() {
        new UidRange(123, 123);
        new UidRange(0, 0);
        new UidRange(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    public void testNegativeUidsDisallowed() {
        try {
            new UidRange(-2, 100);
            fail("Exception not thrown for negative start UID");
        } catch (IllegalArgumentException expected) {
        }

        try {
            new UidRange(-200, -100);
            fail("Exception not thrown for negative stop UID");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testStopLessThanStartDisallowed() {
        final int x = 4195000;
        try {
            new UidRange(x, x - 1);
            fail("Exception not thrown for negative-length UID range");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testGetStartAndEndUser() throws Exception {
        final UidRange uidRangeOfPrimaryUser = new UidRange(
                getUid(USER_SYSTEM, 10000), getUid(USER_SYSTEM, 10100));
        final UidRange uidRangeOfSecondaryUser = new UidRange(
                getUid(MIN_SECONDARY_USER_ID, 10000), getUid(MIN_SECONDARY_USER_ID, 10100));
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getStartUser());
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getEndUser());
        assertEquals(MIN_SECONDARY_USER_ID, uidRangeOfSecondaryUser.getStartUser());
        assertEquals(MIN_SECONDARY_USER_ID, uidRangeOfSecondaryUser.getEndUser());

        final UidRange uidRangeForDifferentUsers = new UidRange(
                getUid(USER_SYSTEM, 10000), getUid(MIN_SECONDARY_USER_ID, 10100));
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getStartUser());
        assertEquals(MIN_SECONDARY_USER_ID, uidRangeOfSecondaryUser.getEndUser());
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testCreateForUser() throws Exception {
        final UidRange uidRangeOfPrimaryUser = UidRange.createForUser(SYSTEM);
        final UidRange uidRangeOfSecondaryUser = UidRange.createForUser(
                UserHandle.of(USER_SYSTEM + 1));
        assertTrue(uidRangeOfPrimaryUser.stop < uidRangeOfSecondaryUser.start);
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getStartUser());
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getEndUser());
        assertEquals(USER_SYSTEM + 1, uidRangeOfSecondaryUser.getStartUser());
        assertEquals(USER_SYSTEM + 1, uidRangeOfSecondaryUser.getEndUser());
    }

    private static void assertSameUids(@NonNull final String msg, @Nullable final Set<UidRange> s1,
            @Nullable final Set<UidRange> s2) {
        assertTrue(msg + " : " + s1 + " unexpectedly different from " + s2,
                UidRange.hasSameUids(s1, s2));
    }

    private static void assertDifferentUids(@NonNull final String msg,
            @Nullable final Set<UidRange> s1, @Nullable final Set<UidRange> s2) {
        assertFalse(msg + " : " + s1 + " unexpectedly equal to " + s2,
                UidRange.hasSameUids(s1, s2));
    }

    // R doesn't have UidRange.hasSameUids, but since S has the module, it does have hasSameUids.
    @Test @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testHasSameUids() {
        final UidRange uids1 = new UidRange(1, 100);
        final UidRange uids2 = new UidRange(3, 300);
        final UidRange uids3 = new UidRange(1, 1000);
        final UidRange uids4 = new UidRange(800, 1000);

        assertSameUids("null <=> null", null, null);
        final Set<UidRange> set1 = new ArraySet<>();
        assertDifferentUids("empty <=> null", set1, null);
        final Set<UidRange> set2 = new ArraySet<>();
        set1.add(uids1);
        assertDifferentUids("uids1 <=> null", set1, null);
        assertDifferentUids("null <=> uids1", null, set1);
        assertDifferentUids("uids1 <=> empty", set1, set2);
        set2.add(uids1);
        assertSameUids("uids1 <=> uids1", set1, set2);
        set1.add(uids2);
        assertDifferentUids("uids1,2 <=> uids1", set1, set2);
        set1.add(uids3);
        assertDifferentUids("uids1,2,3 <=> uids1", set1, set2);
        set2.add(uids3);
        assertDifferentUids("uids1,2,3 <=> uids1,3", set1, set2);
        set2.add(uids2);
        assertSameUids("uids1,2,3 <=> uids1,2,3", set1, set2);
        set1.remove(uids2);
        assertDifferentUids("uids1,3 <=> uids1,2,3", set1, set2);
        set1.add(uids4);
        assertDifferentUids("uids1,3,4 <=> uids1,2,3", set1, set2);
        set2.add(uids4);
        assertDifferentUids("uids1,3,4 <=> uids1,2,3,4", set1, set2);
        assertDifferentUids("uids1,3,4 <=> null", set1, null);
        set2.remove(uids2);
        assertSameUids("uids1,3,4 <=> uids1,3,4", set1, set2);
        set2.remove(uids1);
        assertDifferentUids("uids1,3,4 <=> uids3,4", set1, set2);
        set2.remove(uids3);
        assertDifferentUids("uids1,3,4 <=> uids4", set1, set2);
        set2.remove(uids4);
        assertDifferentUids("uids1,3,4 <=> empty", set1, set2);
        assertDifferentUids("null <=> empty", null, set2);
        assertSameUids("empty <=> empty", set2, new ArraySet<>());
    }

    private static void assertInSameRange(@NonNull final String msg,
            @Nullable final UidRange r1,
            @Nullable final Set<UidRange> s2) {
        assertTrue(msg + " : " + s2 + " unexpectedly is not in range of " + r1,
                UidRange.isRangeSetInUidRange(r1, s2));
    }

    private static void assertNotInSameRange(@NonNull final String msg,
            @Nullable final UidRange r1, @Nullable final Set<UidRange> s2) {
        assertFalse(msg + " : " + s2 + " unexpectedly is in range of " + r1,
                UidRange.isRangeSetInUidRange(r1, s2));
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.S)
    public void testRangeSetInUidRange() {
        final UidRange uids1 = new UidRange(1, 100);
        final UidRange uids2 = new UidRange(3, 300);
        final UidRange uids3 = new UidRange(1, 1000);
        final UidRange uids4 = new UidRange(1, 100);
        final UidRange uids5 = new UidRange(2, 20);
        final UidRange uids6 = new UidRange(3, 30);


        assertInSameRange("null <=> null", null, null);
        assertNotInSameRange("empty <=> null", uids1, null);

        final Set<UidRange> set1 = new ArraySet<>();
        final Set<UidRange> set2 = new ArraySet<>();

        assertNotInSameRange("uids1 <=> null", uids1, null);
        assertInSameRange("null <=> uids1", null, set1);
        assertNotInSameRange("uids1 <=> empty", uids1, set2);

        set2.add(uids1);
        assertInSameRange("uids1 <=> uids1", uids1, set2);

        set2.clear();
        set2.add(uids2);
        assertNotInSameRange("uids1 <=> uids2", uids1, set2);
        set2.clear();
        set2.add(uids3);
        assertNotInSameRange("uids1 <=> uids3", uids1, set2);
        set2.clear();
        set2.add(uids4);
        assertInSameRange("uids1 <=> uids4", uids1, set2);

        set2.clear();
        set2.add(uids5);
        set2.add(uids6);
        assertInSameRange("uids1 <=> uids5, 6", uids1, set2);

        set2.clear();
        set2.add(uids2);
        set2.add(uids6);
        assertNotInSameRange("uids1 <=> uids2, 6", uids1, set2);
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.S)
    public void testRemoveRangeSetFromUidRange() {
        final UidRange uids1 = new UidRange(1, 100);
        final UidRange uids2 = new UidRange(3, 300);
        final UidRange uids3 = new UidRange(1, 1000);
        final UidRange uids4 = new UidRange(1, 100);
        final UidRange uids5 = new UidRange(2, 20);
        final UidRange uids6 = new UidRange(3, 30);
        final UidRange uids7 = new UidRange(30, 39);

        final UidRange uids8 = new UidRange(1, 1);
        final UidRange uids9 = new UidRange(21, 100);
        final UidRange uids10 = new UidRange(1, 2);
        final UidRange uids11 = new UidRange(31, 100);

        final UidRange uids12 = new UidRange(1, 1);
        final UidRange uids13 = new UidRange(21, 29);
        final UidRange uids14 = new UidRange(40, 100);

        assertThrows(NullPointerException.class,
                () -> UidRange.removeRangeSetFromUidRange(null, null));
        Set<UidRange> expected = new ArraySet<>();
        expected.add(uids1);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, null));
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, new ArraySet<>()));

        expected.clear();
        final Set<UidRange> set2 = new ArraySet<>();
        set2.add(uids1);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, set2));
        set2.clear();
        set2.add(uids4);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, set2));

        expected.add(uids1);
        set2.clear();
        set2.add(uids2);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, set2));
        set2.clear();
        set2.add(uids3);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, set2));
        set2.clear();
        set2.add(uids3);
        set2.add(uids6);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, set2));

        expected.clear();
        expected.add(uids8);
        expected.add(uids9);
        set2.clear();
        set2.add(uids5);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, set2));

        expected.clear();
        expected.add(uids10);
        expected.add(uids11);
        set2.clear();
        set2.add(uids6);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, set2));

        expected.clear();
        expected.add(uids12);
        expected.add(uids13);
        expected.add(uids14);
        set2.clear();
        set2.add(uids5);
        set2.add(uids7);
        assertEquals(expected, UidRange.removeRangeSetFromUidRange(uids1, set2));
    }

    private static void assertRangeOverlaps(@NonNull final String msg,
            @Nullable final Set<UidRange> s1,
            @Nullable final Set<UidRange> s2) {
        assertTrue(msg + " : " + s2 + " unexpectedly does not overlap with " + s1,
                UidRange.doesRangeSetOverlap(s1, s2));
    }

    private static void assertRangeDoesNotOverlap(@NonNull final String msg,
            @Nullable final Set<UidRange> s1, @Nullable final Set<UidRange> s2) {
        assertFalse(msg + " : " + s2 + " unexpectedly ovelaps with " + s1,
                UidRange.doesRangeSetOverlap(s1, s2));
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.S)
    public void testRangeSetOverlap() {
        final UidRange uids1 = new UidRange(1, 100);
        final UidRange uids2 = new UidRange(3, 300);
        final UidRange uids3 = new UidRange(1, 1000);
        final UidRange uids4 = new UidRange(1, 100);
        final UidRange uids5 = new UidRange(2, 20);
        final UidRange uids6 = new UidRange(3, 30);
        final UidRange uids7 = new UidRange(0, 0);
        final UidRange uids8 = new UidRange(1, 500);
        final UidRange uids9 = new UidRange(101, 200);


        assertRangeDoesNotOverlap("null <=> null", null, null);

        final Set<UidRange> set1 = new ArraySet<>();
        final Set<UidRange> set2 = new ArraySet<>();
        assertRangeDoesNotOverlap("empty <=> null", set1, null);
        assertRangeDoesNotOverlap("empty <=> null", null, set2);
        assertRangeDoesNotOverlap("empty <=> null", set1, set2);

        set2.add(uids1);
        set1.add(uids1);
        assertRangeOverlaps("uids1 <=> uids1", set1, set2);

        set1.clear();
        set1.add(uids1);
        set2.clear();
        set2.add(uids2);
        assertRangeOverlaps("uids1 <=> uids2", set1, set2);

        set1.clear();
        set1.add(uids1);
        set2.clear();
        set2.add(uids3);
        assertRangeOverlaps("uids1 <=> uids3", set1, set2);

        set1.clear();
        set1.add(uids1);
        set2.clear();
        set2.add(uids4);
        assertRangeOverlaps("uids1 <=> uids4", set1, set2);

        set1.clear();
        set1.add(uids1);
        set2.clear();
        set2.add(uids5);
        set2.add(uids6);
        assertRangeOverlaps("uids1 <=> uids5,6", set1, set2);

        set1.clear();
        set1.add(uids1);
        set2.clear();
        set2.add(uids7);
        assertRangeDoesNotOverlap("uids1 <=> uids7", set1, set2);

        set1.clear();
        set1.add(uids1);
        set2.clear();
        set2.add(uids9);
        assertRangeDoesNotOverlap("uids1 <=> uids9", set1, set2);

        set1.clear();
        set1.add(uids1);
        set2.clear();
        set2.add(uids8);
        assertRangeOverlaps("uids1 <=> uids8", set1, set2);


        set1.clear();
        set1.add(uids1);
        set2.clear();
        set2.add(uids8);
        set2.add(uids7);
        assertRangeOverlaps("uids1 <=> uids7, 8", set1, set2);
    }
}
