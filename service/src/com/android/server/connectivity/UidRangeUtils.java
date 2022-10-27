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

import com.android.internal.annotations.VisibleForTesting;

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

    public static final class Diff {
        public UidRange intersection;
        public final List<UidRange> onlyA = new ArrayList<>(2);
        public final List<UidRange> onlyB = new ArrayList<>(2);

        public static void computeInto(Diff diff, UidRange A, UidRange B) {
            diff.onlyA.clear();
            diff.onlyB.clear();
            if (A.equals(B)) {
                diff.intersection = A;
                return;
            }
            int startX = Math.max(A.start, B.start);
            int stopX = Math.min(A.stop, B.stop);
            if (stopX < startX) {
                // no intersection
                diff.intersection = null;
                diff.onlyA.add(A);
                diff.onlyB.add(B);
                return;
            }
            diff.intersection = new UidRange(startX, stopX);
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
        public static Diff compute(UidRange A, UidRange B) {
            Diff diff = new Diff();
            computeInto(diff, A, B);
            return diff;
        }
        @Override
        public String toString() {
            return "UidRangeUtils.Diff{intersection=" + intersection + ", onlyA=" + onlyA + ", "
                    + "onlyB=" + onlyB + "}";
        }
    }

    public static final class SetDiff {
        public final List<UidRange> intersection;
        public final List<UidRange> onlyA;
        public final List<UidRange> onlyB;

        public SetDiff() {
            this.intersection = new ArrayList<>();
            this.onlyA = new ArrayList<>();
            this.onlyB = new ArrayList<>();
        }
        public SetDiff(@NonNull List<UidRange> intersection, @NonNull List<UidRange> onlyA,
                    @NonNull List<UidRange> onlyB) {
            Objects.requireNonNull(intersection);
            Objects.requireNonNull(onlyA);
            Objects.requireNonNull(onlyB);
            this.intersection = intersection;
            this.onlyA = onlyA;
            this.onlyB = onlyB;
        }

        /**
         * Computes the differences between two collections of UID ranges. Assumes there are no
         * duplicate or overlapping ranges within either individual collection.
         *
         * @param setA first collection of ranges
         * @param setB second collection of ranges
         * @return {@code SetDiff} containing common and different ranges between the sets
         * @hide
         */
        public static SetDiff compute(Collection<UidRange> setA, Collection<UidRange> setB) {
            SetDiff setDiff = new SetDiff();
            setDiff.onlyA.addAll(setA);
            setDiff.onlyB.addAll(setB);
            Diff diff = new Diff();
            for (int i = 0; i < setDiff.onlyA.size(); i++) {
                UidRange A = setDiff.onlyA.get(i);
                final Iterator<UidRange> iteratorB = setDiff.onlyB.iterator();
                while (iteratorB.hasNext()) {
                    UidRange B = iteratorB.next();
                    if (A.equals(B)) {
                        setDiff.onlyA.remove(i--);
                        iteratorB.remove();
                        setDiff.intersection.add(A);
                        break;
                    }
                    Diff.computeInto(diff, A, B);
                    if (diff.intersection != null) {
                        setDiff.onlyA.remove(i--);
                        iteratorB.remove();
                        setDiff.add(diff);
                        break;
                    }
                }
            }
            return setDiff;
        }

        private void add(Diff diff) {
            if (diff.intersection != null) {
                this.intersection.add(diff.intersection);
            }
            this.onlyA.addAll(diff.onlyA);
            this.onlyB.addAll(diff.onlyB);
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
            if (o instanceof SetDiff) {
                SetDiff other = (SetDiff) o;

                return other.getIntersectionSet().equals(getIntersectionSet())
                        && other.getOnlyASet().equals(getOnlyASet())
                        && other.getOnlyBSet().equals(getOnlyBSet());
            }
            return false;
        }

        @Override
        public String toString() {
            return "UidRangeUtils.SetDiff{intersection=" + intersection + ", onlyA=" + onlyA + ", "
                    + "onlyB=" + onlyB + "}";
        }
    }
}
