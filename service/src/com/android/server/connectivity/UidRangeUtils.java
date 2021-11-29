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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.UidRange;
import android.util.ArraySet;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Utility class for UidRange
 *
 * @hide
 */
public final class UidRangeUtils {
    /**
     * Check if given uid range set is within the uid range
     * @param uids uid range in which uidRangeSet is checked to be in range.
     * @param uidRangeSet uid range set to be be checked if it is in range of uids
     * @return true uidRangeSet is in the range of uids
     * @hide
     */
    public static boolean isRangeSetInUidRange(@Nullable UidRange uids,
            @Nullable Set<UidRange> uidRangeSet) {
        if (null == uids) return (null == uidRangeSet) || (uidRangeSet.size() == 0);
        if (null == uidRangeSet || uidRangeSet.size() == 0) {
            return false;
        }
        for (UidRange range : uidRangeSet) {
            if (!uids.contains(range.start) || !uids.contains(range.stop)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Remove given uid ranges set from a uid range
     * @param uids uid range from which uidRangeSet will be removed
     * @param uidRangeSet uid range set to be removed from uids. Set should be in increasing order
     *                    without overlap else IllegalArgumentException is thrown.
     * @hide
     */
    public static Set<UidRange> removeRangeSetFromUidRange(@NonNull UidRange uids,
            @Nullable Set<UidRange> uidRangeSet) {
        Objects.requireNonNull(uids);
        Set<UidRange> filteredRangeSet = new ArraySet<UidRange>();
        if (null == uidRangeSet || (uidRangeSet.size() == 0)
                || !isRangeSetInUidRange(uids, uidRangeSet)) {
            filteredRangeSet.add(uids);
            return filteredRangeSet;
        }

        int start = uids.start;
        UidRange previousRange = null;
        for (UidRange uidRange : uidRangeSet) {
            if (previousRange != null) {
                if (previousRange.stop > uidRange.start) {
                    throw new IllegalArgumentException("UID ranges are not increasing order");
                }
            }
            if (uidRange.start > start) {
                filteredRangeSet.add(new UidRange(start, uidRange.start - 1));
            }
            start = uidRange.stop + 1;
        }
        if (start < uids.stop) {
            filteredRangeSet.add(new UidRange(start, uids.stop));
        }
        return filteredRangeSet;
    }

    /**
     * Compare if the given UID range sets have overlapping uids
     * @param uidRangeSet1 first uid range set to check for overlap
     * @param uidRangeSet2 second uid range set to check for overlap
     * @hide
     */
    public static boolean doesRangeSetOverlap(@Nullable Set<UidRange> uidRangeSet1,
            @Nullable Set<UidRange> uidRangeSet2) {
        if (uidRangeSet1 == null || uidRangeSet1.size() == 0
                || uidRangeSet2 == null || uidRangeSet2.size() == 0) {
            return false;
        }
        for (UidRange range1 : uidRangeSet1) {
            for (UidRange range2 : uidRangeSet2) {
                if (range1.contains(range2.start) || range1.contains(range2.stop)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Convert a list of uid to set of UidRanges.
     * @param uids list of uids
     * @return set of UidRanges
     * @hide
     */
    public static Set<UidRange> convertListToUidRange(List<Integer> uids) {
        Set<UidRange> uidRangeSet = new ArraySet<UidRange>();
        if (uids == null || uids.size() == 0) {
            return uidRangeSet;
        }
        Collections.sort(uids);
        int start = uids.get(0);
        int stop = start;

        for (Integer i : uids) {
            if (i <= stop + 1) {
                stop = i;
            } else {
                uidRangeSet.add(new UidRange(start, stop));
                start = i;
                stop = i;
            }
        }
        uidRangeSet.add(new UidRange(start, stop));
        return uidRangeSet;
    }
}
