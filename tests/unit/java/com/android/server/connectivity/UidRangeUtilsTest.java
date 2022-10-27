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

package com.android.server.connectivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.UidRange;
import android.os.Build;
import android.util.ArraySet;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Tests for UidRangeUtils.
 *
 * Build, install and run with:
 *  runtest frameworks-net -c com.android.server.connectivity.UidRangeUtilsTest
 */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class UidRangeUtilsTest {
    private static void assertInSameRange(@NonNull final String msg,
            @Nullable final UidRange r1,
            @Nullable final Set<UidRange> s2) {
        assertTrue(msg + " : " + s2 + " unexpectedly is not in range of " + r1,
                UidRangeUtils.isRangeSetInUidRange(r1, s2));
    }

    private static void assertNotInSameRange(@NonNull final String msg,
            @Nullable final UidRange r1, @Nullable final Set<UidRange> s2) {
        assertFalse(msg + " : " + s2 + " unexpectedly is in range of " + r1,
                UidRangeUtils.isRangeSetInUidRange(r1, s2));
    }

    @Test @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
    public void testRangeSetInUidRange() {
        final UidRange uids1 = new UidRange(1, 100);
        final UidRange uids2 = new UidRange(3, 300);
        final UidRange uids3 = new UidRange(1, 1000);
        final UidRange uids4 = new UidRange(1, 100);
        final UidRange uids5 = new UidRange(2, 20);
        final UidRange uids6 = new UidRange(3, 30);

        assertThrows(NullPointerException.class,
                () -> UidRangeUtils.isRangeSetInUidRange(null, null));
        assertThrows(NullPointerException.class,
                () -> UidRangeUtils.isRangeSetInUidRange(uids1, null));

        final ArraySet<UidRange> set1 = new ArraySet<>();
        final ArraySet<UidRange> set2 = new ArraySet<>();

        assertThrows(NullPointerException.class,
                () -> UidRangeUtils.isRangeSetInUidRange(null, set1));
        assertInSameRange("uids1 <=> empty", uids1, set2);

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

    @Test @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
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

        final UidRange uids15 = new UidRange(3, 30);
        final UidRange uids16 = new UidRange(31, 39);

        assertThrows(NullPointerException.class,
                () -> UidRangeUtils.removeRangeSetFromUidRange(null, null));
        Set<UidRange> expected = new ArraySet<>();
        expected.add(uids1);
        assertThrows(NullPointerException.class,
                () -> UidRangeUtils.removeRangeSetFromUidRange(uids1, null));
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, new ArraySet<>()));

        expected.clear();
        final ArraySet<UidRange> set2 = new ArraySet<>();
        set2.add(uids1);
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));
        set2.clear();
        set2.add(uids4);
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));

        expected.add(uids10);
        set2.clear();
        set2.add(uids2);
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));

        expected.clear();
        set2.clear();
        set2.add(uids3);
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));

        set2.clear();
        set2.add(uids3);
        set2.add(uids6);
        assertThrows(IllegalArgumentException.class,
                () -> UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));

        expected.clear();
        expected.add(uids8);
        expected.add(uids9);
        set2.clear();
        set2.add(uids5);
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));

        expected.clear();
        expected.add(uids10);
        expected.add(uids11);
        set2.clear();
        set2.add(uids6);
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));

        expected.clear();
        expected.add(uids12);
        expected.add(uids13);
        expected.add(uids14);
        set2.clear();
        set2.add(uids5);
        set2.add(uids7);
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));

        expected.clear();
        expected.add(uids10);
        expected.add(uids14);
        set2.clear();
        set2.add(uids15);
        set2.add(uids16);
        assertEquals(expected, UidRangeUtils.removeRangeSetFromUidRange(uids1, set2));
    }

    private static void assertRangeOverlaps(@NonNull final String msg,
            @Nullable final Set<UidRange> s1,
            @Nullable final Set<UidRange> s2) {
        assertTrue(msg + " : " + s2 + " unexpectedly does not overlap with " + s1,
                UidRangeUtils.doesRangeSetOverlap(s1, s2));
    }

    private static void assertRangeDoesNotOverlap(@NonNull final String msg,
            @Nullable final Set<UidRange> s1, @Nullable final Set<UidRange> s2) {
        assertFalse(msg + " : " + s2 + " unexpectedly ovelaps with " + s1,
                UidRangeUtils.doesRangeSetOverlap(s1, s2));
    }

    @Test @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
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

        assertThrows(NullPointerException.class,
                () -> UidRangeUtils.doesRangeSetOverlap(null, null));

        final ArraySet<UidRange> set1 = new ArraySet<>();
        final ArraySet<UidRange> set2 = new ArraySet<>();
        assertThrows(NullPointerException.class,
                () -> UidRangeUtils.doesRangeSetOverlap(set1, null));
        assertThrows(NullPointerException.class,
                () -> UidRangeUtils.doesRangeSetOverlap(null, set2));
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

    @Test @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
    public void testConvertListToUidRange() {
        final UidRange uids1 = new UidRange(1, 1);
        final UidRange uids2 = new UidRange(1, 2);
        final UidRange uids3 = new UidRange(100, 100);
        final UidRange uids4 = new UidRange(10, 10);

        final UidRange uids5 = new UidRange(10, 14);
        final UidRange uids6 = new UidRange(20, 24);

        final Set<UidRange> expected = new ArraySet<>();
        final List<Integer> input = new ArrayList<Integer>();

        assertThrows(NullPointerException.class, () -> UidRangeUtils.convertListToUidRange(null));
        assertEquals(expected, UidRangeUtils.convertListToUidRange(input));

        input.add(1);
        expected.add(uids1);
        assertEquals(expected, UidRangeUtils.convertListToUidRange(input));

        input.add(2);
        expected.clear();
        expected.add(uids2);
        assertEquals(expected, UidRangeUtils.convertListToUidRange(input));

        input.clear();
        input.add(1);
        input.add(100);
        expected.clear();
        expected.add(uids1);
        expected.add(uids3);
        assertEquals(expected, UidRangeUtils.convertListToUidRange(input));

        input.clear();
        input.add(100);
        input.add(1);
        expected.clear();
        expected.add(uids1);
        expected.add(uids3);
        assertEquals(expected, UidRangeUtils.convertListToUidRange(input));

        input.clear();
        input.add(100);
        input.add(1);
        input.add(2);
        input.add(1);
        input.add(10);
        expected.clear();
        expected.add(uids2);
        expected.add(uids4);
        expected.add(uids3);
        assertEquals(expected, UidRangeUtils.convertListToUidRange(input));

        input.clear();
        input.add(10);
        input.add(11);
        input.add(12);
        input.add(13);
        input.add(14);
        input.add(20);
        input.add(21);
        input.add(22);
        input.add(23);
        input.add(24);
        expected.clear();
        expected.add(uids5);
        expected.add(uids6);
        assertEquals(expected, UidRangeUtils.convertListToUidRange(input));
    }

    @Test @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
    public void testConvertArrayToUidRange() {
        final UidRange uids1_1 = new UidRange(1, 1);
        final UidRange uids1_2 = new UidRange(1, 2);
        final UidRange uids100_100 = new UidRange(100, 100);
        final UidRange uids10_10 = new UidRange(10, 10);

        final UidRange uids10_14 = new UidRange(10, 14);
        final UidRange uids20_24 = new UidRange(20, 24);

        final Set<UidRange> expected = new ArraySet<>();
        int[] input = new int[0];

        assertThrows(NullPointerException.class, () -> UidRangeUtils.convertArrayToUidRange(null));
        assertEquals(expected, UidRangeUtils.convertArrayToUidRange(input));

        input = new int[] {1};
        expected.add(uids1_1);
        assertEquals(expected, UidRangeUtils.convertArrayToUidRange(input));

        input = new int[]{1, 2};
        expected.clear();
        expected.add(uids1_2);
        assertEquals(expected, UidRangeUtils.convertArrayToUidRange(input));

        input = new int[]{1, 100};
        expected.clear();
        expected.add(uids1_1);
        expected.add(uids100_100);
        assertEquals(expected, UidRangeUtils.convertArrayToUidRange(input));

        input = new int[]{100, 1};
        expected.clear();
        expected.add(uids1_1);
        expected.add(uids100_100);
        assertEquals(expected, UidRangeUtils.convertArrayToUidRange(input));

        input = new int[]{100, 1, 2, 1, 10};
        expected.clear();
        expected.add(uids1_2);
        expected.add(uids10_10);
        expected.add(uids100_100);
        assertEquals(expected, UidRangeUtils.convertArrayToUidRange(input));

        input = new int[]{10, 11, 12, 13, 14, 20, 21, 22, 23, 24};
        expected.clear();
        expected.add(uids10_14);
        expected.add(uids20_24);
        assertEquals(expected, UidRangeUtils.convertArrayToUidRange(input));
    }

    private static void assertDiffMatchesReversibly(String msg, UidRangeUtils.SetDiff expected,
            List<UidRange> setA, List<UidRange> setB) {
        final UidRangeUtils.SetDiff diff = UidRangeUtils.SetDiff.compute(
                setA /* setA */, setB /* setB */);
        assertEquals(msg + " : comparing ranges " + setA + " and " + setB + ": ",
                expected,
                diff);

        final UidRangeUtils.SetDiff reversedExpected = new UidRangeUtils.SetDiff(
                expected.intersection, expected.onlyB, expected.onlyA);
        final UidRangeUtils.SetDiff reversedDiff = UidRangeUtils.SetDiff.compute(
                setB /* setA */, setA /* setB */);
        assertEquals(msg + " : reversed: comparing ranges, now " + setB + " and "
                        + setA + ": ",
                reversedExpected,
                reversedDiff);
    }

    private static void assertDiffMatchesReversiblyAndShuffled(UidRangeUtils.SetDiff expected,
            List<UidRange> setA, List<UidRange> setB) {
        assertDiffMatchesReversibly("unshuffled", expected, setA, setB);
        List<UidRange> listA = new ArrayList<>(setA);
        List<UidRange> listB = new ArrayList<>(setB);

        Random r = new Random(7); // consistent random seed

        if (listA.size() <= 1 && listB.size() <= 1) {
            return;
        }

        // shuffle 20 times (or just reverse once if both lists are <= 2 items)
        for (int i = 0; i < 20; i++) {
            if (listA.size() <= 2) {
                Collections.reverse(listA);
            } else {
                Collections.shuffle(listA, r);
            }
            if (listB.size() <= 2) {
                Collections.reverse(listA);
            } else {
                Collections.shuffle(listB, r);
            }
            assertDiffMatchesReversibly("shuffle " + i, expected, listA, listB);
            if (listA.size() <= 2 && listB.size() <= 2) {
                break;
            }
        }
    }

    @Test @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
    public void testComputeSetDiff() {
        final UidRange uids1_1 = new UidRange(1, 1);
        final UidRange uids1_2 = new UidRange(1, 2);
        final UidRange uids1_3 = new UidRange(1, 3);
        final UidRange uids1_5 = new UidRange(1, 5);
        final UidRange uids1_7 = new UidRange(1, 7);
        final UidRange uids1_10 = new UidRange(1, 10);
        final UidRange uids3_3 = new UidRange(3, 3);
        final UidRange uids3_5 = new UidRange(3, 5);
        final UidRange uids3_7 = new UidRange(3, 7);
        final UidRange uids4_5 = new UidRange(4, 5);
        final UidRange uids6_6 = new UidRange(6, 6);
        final UidRange uids6_7 = new UidRange(6, 7);
        final UidRange uids6_10 = new UidRange(6, 10);
        final UidRange uids7_7 = new UidRange(7, 7);
        final UidRange uids7_10 = new UidRange(7, 10);
        final UidRange uids8_9 = new UidRange(8, 9);
        final UidRange uids8_10 = new UidRange(8, 10);
        final UidRange uids9_9 = new UidRange(9, 9);
        final UidRange uids10_10 = new UidRange(10, 10);
        final List<UidRange> setA = new ArrayList<>();
        final List<UidRange> setB = new ArrayList<>();
        final List<UidRange> emptyRanges = new ArrayList<>();
        UidRangeUtils.SetDiff expected;

        // EMPTY
        //   Both empty
        expected = new UidRangeUtils.SetDiff(emptyRanges, emptyRanges, emptyRanges);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        //   One empty
        setA.add(uids1_5);
        expected = new UidRangeUtils.SetDiff(emptyRanges, setA, emptyRanges);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        // IDENTICAL
        //   Single range, Same
        setB.add(uids1_5);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.addAll(setA);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        //   Multiple ranges, Same
        setA.add(uids7_10);
        setB.add(uids7_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.addAll(setA);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        //   Multiple ranges, Touching
        setA.clear();
        setB.clear();
        setA.add(uids1_5);
        setA.add(uids6_10);
        setB.add(uids1_5);
        setB.add(uids6_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.addAll(setA);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        // CONTAINED RANGES
        //   setB contains setA, Start-aligned, and reversed
        setA.clear();
        setB.clear();
        setA.add(uids1_5);
        setB.add(uids1_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids1_5);
        expected.onlyB.add(uids6_10);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        //   setB contains setA, Stop-aligned, and reversed
        setA.clear();
        setB.clear();
        setA.add(uids6_10);
        setB.add(uids1_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids6_10);
        expected.onlyB.add(uids1_5);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        //   setB contains setA, Middle, and reversed
        setA.clear();
        setB.clear();
        setA.add(uids3_7);
        setB.add(uids1_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids3_7);
        expected.onlyB.add(uids1_2);
        expected.onlyB.add(uids8_10);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        // PARTIAL OVERLAP
        //   setA Starts within setB, but Stops after, and reversed
        setA.clear();
        setB.clear();
        setA.add(uids6_10);
        setB.add(uids3_7);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids6_7);
        expected.onlyA.add(uids8_10);
        expected.onlyB.add(uids3_5);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        // SINGLE NUMBER OVERLAPS
        setA.clear();
        setB.clear();
        setA.add(uids1_3);
        setA.add(uids6_7);
        setB.add(uids3_5);
        setB.add(uids7_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids3_3);
        expected.intersection.add(uids7_7);
        expected.onlyA.add(uids1_2);
        expected.onlyA.add(uids6_6);
        expected.onlyB.add(uids4_5);
        expected.onlyB.add(uids8_10);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        // SINGLE NUMBERS
        setA.clear();
        setB.clear();
        setA.add(uids1_1);
        setA.add(uids7_7);
        setB.add(uids7_7);
        setB.add(uids10_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids7_7);
        expected.onlyA.add(uids1_1);
        expected.onlyB.add(uids10_10);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        // NO SHARED RANGES - no overlap
        setA.clear();
        setB.clear();
        setA.add(uids1_1);
        setA.add(uids3_5);
        setB.add(uids6_7);
        setB.add(uids8_10);
        expected = new UidRangeUtils.SetDiff(emptyRanges /* intersection */, setA, setB);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        // COMBINATIONS
        //   Combo 1
        setA.clear();
        setB.clear();
        setA.add(uids3_5);
        setA.add(uids7_10);
        setB.add(uids1_5);
        setB.add(uids7_7);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids3_5);
        expected.intersection.add(uids7_7);
        expected.onlyA.add(uids8_10);
        expected.onlyB.add(uids1_2);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        //   Combo 2
        setA.clear();
        setB.clear();
        setA.add(uids1_2);
        setA.add(uids3_5);
        setA.add(uids8_10);
        setB.add(uids1_7);
        setB.add(uids10_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids1_2);
        expected.intersection.add(uids3_5);
        expected.intersection.add(uids10_10);
        expected.onlyA.add(uids8_9);
        expected.onlyB.add(uids6_7);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        //   Combo 3
        setA.clear();
        setB.clear();
        setA.add(uids1_3);
        setA.add(uids7_7);
        setA.add(uids9_9);
        setB.add(uids1_2);
        setB.add(uids6_7);
        setB.add(uids9_9);
        setB.add(uids10_10);
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(uids1_2);
        expected.intersection.add(uids7_7);
        expected.intersection.add(uids9_9);
        expected.onlyA.add(uids3_3);
        expected.onlyB.add(uids6_6);
        expected.onlyB.add(uids10_10);
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);

        // And now, a common test case that once caused an array index -1 crash on-device...
        setA.clear();
        setB.clear();
        setA.add(new UidRange(1, 10162));
        setA.add(new UidRange(10164, 20162));
        setA.add(new UidRange(20164, 99999));
        setB.add(new UidRange(1, 10164));
        setB.add(new UidRange(10166, 20164));
        setB.add(new UidRange(20166, 99999));
        expected = new UidRangeUtils.SetDiff();
        expected.intersection.add(new UidRange(1, 10162));
        expected.intersection.add(new UidRange(10164, 10164));
        expected.intersection.add(new UidRange(10166, 20162));
        expected.intersection.add(new UidRange(20164, 20164));
        expected.intersection.add(new UidRange(20166, 99999));
        expected.onlyA.add(new UidRange(10165, 10165));
        expected.onlyA.add(new UidRange(20165, 20165));
        expected.onlyB.add(new UidRange(10163, 10163));
        expected.onlyB.add(new UidRange(20163, 20163));
        assertDiffMatchesReversiblyAndShuffled(expected, setA, setB);
    }
}
