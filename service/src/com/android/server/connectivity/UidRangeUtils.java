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
import android.net.UidRange;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
    public static boolean isRangeSetInUidRange(@NonNull UidRange uids,
            @NonNull Set<UidRange> uidRangeSet) {
        Objects.requireNonNull(uids);
        Objects.requireNonNull(uidRangeSet);
        if (uidRangeSet.size() == 0) {
            return true;
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
     * @param uidRangeSet uid range set to be removed from uids.
     * WARNING : This function requires the UidRanges in uidRangeSet to be disjoint
     * WARNING : This function requires the arrayset to be iterated in increasing order of the
     *                    ranges. Today this is provided by the iteration order stability of
     *                    ArraySet, and the fact that the code creating this ArraySet always
     *                    creates it in increasing order.
     * Note : if any of the above is not satisfied this function throws IllegalArgumentException
     * TODO : remove these limitations
     * @hide
     */
    public static ArraySet<UidRange> removeRangeSetFromUidRange(@NonNull UidRange uids,
            @NonNull ArraySet<UidRange> uidRangeSet) {
        Objects.requireNonNull(uids);
        Objects.requireNonNull(uidRangeSet);
        final ArraySet<UidRange> filteredRangeSet = new ArraySet<UidRange>();
        if (uidRangeSet.size() == 0) {
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
                start = uidRange.stop + 1;
            } else if (uidRange.start == start) {
                start = uidRange.stop + 1;
            }
            previousRange = uidRange;
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
    public static boolean doesRangeSetOverlap(@NonNull Set<UidRange> uidRangeSet1,
            @NonNull Set<UidRange> uidRangeSet2) {
        Objects.requireNonNull(uidRangeSet1);
        Objects.requireNonNull(uidRangeSet2);

        if (uidRangeSet1.size() == 0 || uidRangeSet2.size() == 0) {
            return false;
        }
        for (UidRange range1 : uidRangeSet1) {
            for (UidRange range2 : uidRangeSet2) {
                if (range1.contains(range2.start) || range1.contains(range2.stop)
                        || range2.contains(range1.start) || range2.contains(range1.stop)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Convert a list of uids to set of UidRanges.
     * @param uids list of uids
     * @return set of UidRanges
     * @hide
     */
    public static ArraySet<UidRange> convertListToUidRange(@NonNull List<Integer> uids) {
        Objects.requireNonNull(uids);
        final ArraySet<UidRange> uidRangeSet = new ArraySet<UidRange>();
        if (uids.size() == 0) {
            return uidRangeSet;
        }
        List<Integer> uidsNew = new ArrayList<>(uids);
        Collections.sort(uidsNew);
        int start = uidsNew.get(0);
        int stop = start;

        for (Integer i : uidsNew) {
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

    /**
     * Convert an array of uids to set of UidRanges.
     * @param uids array of uids
     * @return set of UidRanges
     * @hide
     */
    public static ArraySet<UidRange> convertArrayToUidRange(@NonNull int[] uids) {
        Objects.requireNonNull(uids);
        final ArraySet<UidRange> uidRangeSet = new ArraySet<UidRange>();
        if (uids.length == 0) {
            return uidRangeSet;
        }
        int[] uidsNew = uids.clone();
        Arrays.sort(uidsNew);
        int start = uidsNew[0];
        int stop = start;

        for (int i : uidsNew) {
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

    private static int compare(UidRange range1, UidRange range2) {
        return range1.start - range2.start;
    }

    /**
     * Sort the given UidRange array.
     *
     * @param ranges The array of UidRange which is going to be sorted.
     * @return Array of UidRange.
     */
    public static UidRange[] sortRangesByStartUid(UidRange[] ranges) {
        final ArrayList uidRanges = new ArrayList(Arrays.asList(ranges));
        Collections.sort(uidRanges, UidRangeUtils::compare);
        return (UidRange[]) uidRanges.toArray(new UidRange[0]);
    }

    /**
     * Check if the given sorted UidRange array contains overlap or not.
     *
     * Note that the sorted UidRange array must be sorted by increasing lower bound. If it's not,
     * the behavior is undefined.
     *
     * @param ranges The sorted UidRange array which is going to be checked if there is an overlap
     *               or not.
     * @return A boolean to indicate if the given sorted UidRange array contains overlap or not.
     */
    public static boolean sortedRangesContainOverlap(UidRange[] ranges) {
        final ArrayList uidRanges = new ArrayList(Arrays.asList(ranges));
        for (int i = 0; i + 1 < uidRanges.size(); i++) {
            if (((UidRange) uidRanges.get(i + 1)).start <= ((UidRange) uidRanges.get(i)).stop) {
                return true;
            }
        }

        return false;
    }

    /**
     * Given any collection of UID ranges, return the canonical set of UID ranges.
     *
     * The canonical set has the minimum possible number of sets and no overlaps.
     *
     * @param ranges the ranges
     * @return the canonical set
     */
    public static Set<UidRange> canonicalize(@NonNull final Collection<UidRange> ranges) {
        int size = ranges.size();
        // Starts and ends are sorted arrays. They contain the start and end of each range at
        // matching indices.
        final int[] starts = new int[size]; // max possible size for this array
        final int[] ends = new int[size]; // max possible size for this array
        size = 0; // no ranges added yet
        for (UidRange range : ranges) {
            if (range.stop <= range.start) continue;
            int insertionPoint = Arrays.binarySearch(starts, 0, size, range.start);
            // Get insertion point whether |range.start| is in the array or not.
            if (insertionPoint < 0) insertionPoint = -(insertionPoint - 1);
            if (insertionPoint > size) {
                // The range starts after the last start.
                if (size <= 0 || ends[size - 1] < range.start - 1) {
                    // The range starts after the last end, or the arrays are empty. Add the
                    // range at the end.
                    starts[insertionPoint] = range.start;
                    ends[insertionPoint] = range.stop;
                    size += 1;
                } else if (range.stop > ends[size - 1]) {
                    // The range starts in the middle of the last range, and it extends past the
                    // end of the last range in the arrays. Extend the last range in the arrays.
                    ends[insertionPoint] = range.stop;
                }
                // Else this range was entirely contained in the last range, do nothing.
            } else if (range.stop < starts[insertionPoint] - 1) {
                // Whole range is before the insertion point. Insert this range here.
                System.arraycopy(starts, insertionPoint, starts, insertionPoint + 1,
                        size - insertionPoint);
                System.arraycopy(ends, insertionPoint, starts, insertionPoint + 1,
                        size - insertionPoint);
                starts[insertionPoint] = range.start;
                ends[insertionPoint] = range.stop;
                size += 1;
            } else {
                // This range starts before the one at the insertion point, but extends into
                // it. Maybe it ends in the middle of it, or it extends past its end. Either way,
                // enlarge the range at the insertion point to start at the start of this range.
                starts[insertionPoint] = range.start;
                if (range.stop <= ends[insertionPoint]) {
                    // Partial overlap with the range at the insertion point. The range at the
                    // insertion point has already been enlarged, nothing left to do.
                    continue;
                } else if (insertionPoint >= size || range.stop < starts[insertionPoint + 1] - 1) {
                    // The range at the insertion point was the last range, or this range stops
                    // before the start of the range after the insertion point. Extend the end of
                    // the range at the insertion point to cover this whole range.
                    ends[insertionPoint] = range.stop;
                } else {
                    // This range starts before the one at the insertion point, and it ends
                    // past the start of the next one. Find the range inside which this range
                    // ends.
                    int extent = Arrays.binarySearch(ends, insertionPoint, size, range.stop);
                    // Get insertion point whether |range.stop| is in the array or not.
                    if (extent < 0) extent = -(extent - 1);
                    if (extent >= size) {
                        // This range extends past the size of the last range. Set the end
                        // of the range at the insertion point and truncate.
                        ends[insertionPoint] = range.stop;
                        size = insertionPoint + 1;
                    } else if (range.stop < starts[extent] - 1) {
                        // This range extends up to the range at |extent|, but ends before
                        // its start.
                        ends[insertionPoint] = range.stop;
                        System.arraycopy(starts, extent, starts, insertionPoint, size - extent + 1);
                        System.arraycopy(ends, extent, ends, insertionPoint, size - extent + 1);
                        size -= extent - insertionPoint;
                    } else {
                        // This range lands in the middle of the range at |extent|
                        System.arraycopy(starts, extent, starts, insertionPoint + 1, size - extent);
                        System.arraycopy(ends, extent, ends, insertionPoint, size - extent + 1);
                        size -= extent - insertionPoint;
                    }
                }
            }
        }
        final ArraySet<UidRange> result = new ArraySet<>();
        for (int i = 0; i < size; ++i) {
            result.add(new UidRange(starts[i], ends[i]));
        }
        return result;
    }
}
