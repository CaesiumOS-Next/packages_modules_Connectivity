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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
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
    public static boolean doesRangeSetOverlap(@NonNull Collection<UidRange> uidRangeSet1,
            @NonNull Collection<UidRange> uidRangeSet2) {
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

    /**
     * Compare two ranges. The range with a lower start point is considered to be less.
     * If the start points match, the range with a lower stop point is considered to be less.
     * Otherwise, the ranges are equal.
     * @hide
     */
    public static int compare(UidRange a, UidRange b) {
        int cmp = a.start - b.start;
        if (cmp == 0) {
            cmp = a.stop - b.stop;
        }
        return cmp;
    }

    /**
     * Return a set of ranges that are present in both collections.
     * Overlapping portions of ranges are also returned.
     * @hide
     */
    public static Set<UidRange> intersect(Set<UidRange> rangesA,
            Collection<UidRange> rangesB) {
        return new ArraySet<>(intersect((Collection<UidRange>)rangesA, rangesB));
    }
    /**
     * Return a list of ranges that are present in both collections.
     * Overlapping portions of ranges are also returned.
     * @hide
     */
    public static List<UidRange> intersect(Collection<UidRange> rangesA,
            Collection<UidRange> rangesB) {
        Diff diff = diff(rangesA, rangesB);
        return diff.intersection;
    }
    /**
     * Modify the first collection such that it retains only the ranges present in both.
     * Overlapping portions of ranges are also retained. rangesA will be modified.
     * @hide
     */
    public static void intersectInPlace(@NonNull Collection<UidRange> rangesA,
            Collection<UidRange> rangesB) {
        Objects.requireNonNull(rangesA);
        List<UidRange> rangesAList = rangesA instanceof List ? (List<UidRange>)rangesA
                : new ArrayList<>(rangesA);
        List<UidRange> rangesBList = new ArrayList<>(rangesB);
        Diff setDiff = new Diff(new ArrayList<>() /* intersection */,
                rangesAList, rangesBList);
        diff(setDiff);
        rangesA.retainAll(setDiff.intersection);
        setDiff.intersection.stream().filter(r -> !rangesA.contains(r))
                .forEachOrdered(rangesA::add);
    }

    /**
     * Return a set comprising a set of ranges with the specified ranges excluded.
     * Split existing ranges when necessary to remove part of a range.
     * @hide
     */
    public static Set<UidRange> exclude(Set<UidRange> ranges,
            Collection<UidRange> rangesToExclude) {
        return new ArraySet<>(exclude((Collection<UidRange>)ranges, rangesToExclude));
    }
    /**
     * Return a list comprising a collection of ranges with the specified ranges excluded.
     * Split existing ranges when necessary to remove part of a range.
     * @hide
     */
    public static List<UidRange> exclude(Collection<UidRange> ranges,
            Collection<UidRange> rangesToExclude) {
        Diff diff = diff(ranges, rangesToExclude);
        return diff.onlyA;
    }
    /**
     * Exclude the given collection of ranges from an existing collection.
     * Split existing ranges when necessary to remove part of a range.
     * @hide
     */
    public static void excludeInPlace(@NonNull Collection<UidRange> ranges,
            Collection<UidRange> rangesToExclude) {
        Objects.requireNonNull(ranges);
        List<UidRange> rangesList = ranges instanceof List ? (List<UidRange>)ranges
                : new ArrayList<>(ranges);
        List<UidRange> rangesToExcludeList = new ArrayList<>(rangesToExclude);
        Diff setDiff = new Diff(null /* intersection */, rangesList, rangesToExcludeList);
        diff(setDiff);
        if (ranges != rangesList) {
            ranges.retainAll(rangesList);
            rangesList.stream().filter(r -> !ranges.contains(r)).forEachOrdered(ranges::add);
        }
    }

    /**
     * Return a set comprising a merge of a set of ranges with a collection of ranges.
     * When ranges overlap, existing ranges are expanded.
     * @hide
     */
    public static Set<UidRange> merge(Set<UidRange> ranges,
            Collection<UidRange> rangesToMerge) {
        return new ArraySet<>(merge((Collection<UidRange>)ranges, rangesToMerge));
    }
    /**
     * Return a list comprising a merge of two collections of ranges.
     * When ranges overlap, existing ranges are expanded.
     * @hide
     */
    public static List<UidRange> merge(Collection<UidRange> ranges,
            Collection<UidRange> rangesToMerge) {
        Diff diff = diff(ranges, rangesToMerge);
        List<UidRange> mergedRanges = diff.intersection;
        mergedRanges.addAll(diff.onlyA);
        mergedRanges.addAll(diff.onlyB);
        mergeAdjacentInPlace(mergedRanges);
        return mergedRanges;
    }
    /**
     * Merge an existing collection of ranges with a given collection of ranges.
     * When ranges overlap, existing ranges are expanded. ranges will be modified.
     * @hide
     */
    public static void mergeInPlace(@NonNull Collection<UidRange> ranges,
            Collection<UidRange> rangesToMerge) {
        Objects.requireNonNull(ranges);
        List<UidRange> rangesList = ranges instanceof List ? (List<UidRange>)ranges
                : new ArrayList<>(ranges);
        List<UidRange> rangesToMergeList = new ArrayList<>(rangesToMerge);
        Diff setDiff = new Diff(new ArrayList<>() /* intersection */, rangesList /* rangesA */,
                rangesToMergeList /* rangesB */);
        diff(setDiff);
        rangesList.addAll(setDiff.intersection);
        rangesList.addAll(setDiff.onlyB);
        mergeAdjacentInPlace(rangesList);
        if (ranges != rangesList) {
            ranges.retainAll(rangesList);
            rangesList.stream().filter(r -> !ranges.contains(r)).forEachOrdered(ranges::add);
        }
    }

    /**
     * Combine any ranges that are touching, and return the result as a set.
     * Does not handle overlapping or duplicate ranges.
     * @hide
     */
    public static Set<UidRange> mergeAdjacent(Set<UidRange> ranges) {
        return new ArraySet<>(mergeAdjacent((Collection<UidRange>)ranges));
    }
    /**
     * Combine any ranges that are touching, and return the result as a list.
     * Does not handle overlapping or duplicate ranges.
     * @hide
     */
    public static List<UidRange> mergeAdjacent(Collection<UidRange> ranges) {
        List<UidRange> list = new ArrayList<>(ranges);
        mergeAdjacentInPlace(list);
        return list;
    }
    /**
     * Sort the given list of ranges and combine any ranges that are touching.
     * Does not handle overlapping or duplicate ranges.
     * @hide
     */
    public static void mergeAdjacentInPlace(@NonNull List<UidRange> ranges) {
        Objects.requireNonNull(ranges);
        ranges.sort(UidRangeUtils::compare);
        if (ranges.size() <= 1) {
            return;
        }
        UidRange lastRange = ranges.get(0);
        for (int i = 1; i < ranges.size(); i++) {
            UidRange range = ranges.get(i);
            if (range.start <= lastRange.stop + 1) {
                ranges.remove(i--);
                range = new UidRange(lastRange.start, range.stop);
                ranges.set(i, range);
            }
            lastRange = range;
        }
    }
    /**
     * Combine any ranges in the collection that are touching.
     * Does not handle overlapping or duplicate ranges.
     * @hide
     */
    public static void mergeAdjacentInPlace(Collection<UidRange> ranges) {
        List<UidRange> list = mergeAdjacent(ranges);
        ranges.clear();
        ranges.addAll(list);
    }

    /**
     * Computes the differences between two collections of UID ranges. Assumes there are no
     * duplicate or overlapping ranges within either individual collection.
     *
     * @param setA first collection of ranges
     * @param setB second collection of ranges
     * @return {@code Diff} containing common and different ranges between the sets
     * @hide
     */
    public static Diff diff(@NonNull Collection<UidRange> setA,
            @NonNull Collection<UidRange> setB) {
        Diff setDiff = new Diff();
        setDiff.onlyA.addAll(setA);
        setDiff.onlyB.addAll(setB);
        diff(setDiff);
        return setDiff;
    }

    /**
     * Computes the differences between two lists of UID ranges within an existing Diff
     * object. onlyA represents the first list, and onlyB represents the second list.
     * Assumes the lists are pre-sorted and that there are no duplicate or overlapping
     * ranges within either individual collection.
     *
     * @param setDiff existing Diff object
     * @hide
     */
    public static void diff(@NonNull Diff setDiff) {
        Diff rangeDiff = null;
        for (int i = 0; i < setDiff.onlyA.size(); i++) {
            UidRange A = setDiff.onlyA.get(i);
            final Iterator<UidRange> iteratorB = setDiff.onlyB.iterator();
            while (iteratorB.hasNext()) {
                UidRange B = iteratorB.next();
                if (A.equals(B)) {
                    setDiff.onlyA.remove(i--);
                    iteratorB.remove();
                    if (setDiff.intersection != null) {
                        setDiff.intersection.add(A);
                    }
                    break;
                }
                if (rangeDiff == null) {
                    rangeDiff = new Diff();
                } else {
                    rangeDiff.clear();
                }
                diffInto(rangeDiff, A, B);
                if (!rangeDiff.intersection.isEmpty()) {
                    setDiff.onlyA.remove(i--);
                    iteratorB.remove();
                    setDiff.add(rangeDiff);
                    break;
                }
            }
        }
    }

    /**
     * Compute the difference between two UID ranges and place the result into an existing
     * Diff object.
     *
     * @param diff existing Diff object
     * @param A first range
     * @param B second range
     * @hide
     */
    public static void diffInto(Diff diff, UidRange A, UidRange B) {
        if (A.equals(B)) {
            diff.intersection.add(A);
            return;
        }
        int startX = Math.max(A.start, B.start);
        int stopX = Math.min(A.stop, B.stop);
        if (stopX < startX) {
            // no intersection
            diff.onlyA.add(A);
            diff.onlyB.add(B);
            return;
        }
        diff.intersection.add(new UidRange(startX, stopX));
        if (A.start < startX) {
            diff.onlyA.add(new UidRange(A.start, startX - 1));
        } else if (B.start < startX) {
            diff.onlyB.add(new UidRange(B.start, startX - 1));
        }
        if (A.stop > stopX) {
            diff.onlyA.add(new UidRange(stopX + 1, A.stop));
        } else if (B.stop > stopX) {
            diff.onlyB.add(new UidRange(stopX + 1, B.stop));
        }
    }

    /**
     * Compute the difference between two UID ranges.
     *
     * @param A first range
     * @param B second range
     * @return {@code Diff} containing common and different ranges between the ranges
     * @hide
     */
    public static Diff diff(UidRange A, UidRange B) {
        Diff diff = new Diff();
        diffInto(diff, A, B);
        return diff;
    }

    public static final class Diff {
        public final List<UidRange> intersection;
        public final List<UidRange> onlyA;
        public final List<UidRange> onlyB;

        public Diff() {
            this.intersection = new ArrayList<>();
            this.onlyA = new ArrayList<>();
            this.onlyB = new ArrayList<>();
        }
        public Diff(List<UidRange> intersection, List<UidRange> onlyA, List<UidRange> onlyB) {
            this.intersection = intersection;
            this.onlyA = onlyA;
            this.onlyB = onlyB;
        }

        public void mergeAdjacentInPlace() {
            UidRangeUtils.mergeAdjacentInPlace(intersection);
            UidRangeUtils.mergeAdjacentInPlace(onlyA);
            UidRangeUtils.mergeAdjacentInPlace(onlyB);
        }

        public void clear() {
            this.intersection.clear();
            this.onlyA.clear();
            this.onlyB.clear();
        }

        public void add(Diff diff) {
            if (intersection != null) {
                intersection.addAll(diff.intersection);
            }
            onlyA.addAll(diff.onlyA);
            onlyB.addAll(diff.onlyB);
        }

        public Set<UidRange> getIntersectionSet() {
            return new ArraySet<>(intersection);
        }
        public Set<UidRange> getOnlyASet() {
            return new ArraySet<>(onlyA);
        }
        public Set<UidRange> getOnlyBSet() {
            return new ArraySet<>(onlyB);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof Diff) {
                Diff other = (Diff) o;

                return other.intersection.size() == intersection.size()
                        && other.onlyA.size() == onlyA.size()
                        && other.onlyB.size() == onlyB.size()
                        && getIntersectionSet().containsAll(intersection)
                        && getOnlyASet().containsAll(onlyA)
                        && getOnlyBSet().containsAll(onlyB);
            }
            return false;
        }

        @Override
        public int hashCode() {
            // This is not very efficient, but in practice it should never get called.
            return Objects.hash(getIntersectionSet(), getOnlyASet(), getOnlyBSet());
        }

        @Override
        public String toString() {
            return "UidRangeUtils.Diff{intersection=" + intersection + ", onlyA=" + onlyA + ", "
                    + "onlyB=" + onlyB + "}";
        }
    }
}
