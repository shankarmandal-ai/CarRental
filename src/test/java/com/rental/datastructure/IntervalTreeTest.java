package com.rental.datastructure;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalTreeTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    private LocalDateTime d(int day) {
        return T0.plusDays(day);
    }

    @Test
    void emptyTreeHasNoOverlap() {
        IntervalTree<String> tree = new IntervalTree<>();
        assertFalse(tree.hasOverlap(d(0), d(1)));
        assertTrue(tree.isEmpty());
    }

    @Test
    void detectsOverlapAcrossSeveralIntervals() {
        IntervalTree<String> tree = new IntervalTree<>();
        tree.insert(d(1), d(3), "r1", "r1");
        tree.insert(d(5), d(8), "r2", "r2");
        tree.insert(d(2), d(6), "r3", "r3");
        tree.insert(d(10), d(12), "r4", "r4");
        tree.insert(d(7), d(9), "r5", "r5");

        assertTrue(tree.hasOverlap(d(4), d(5))); // overlaps r3 [2,6)
        assertFalse(tree.hasOverlap(d(9), d(10))); // touches r5 end and r4 start, no actual overlap
        assertTrue(tree.hasOverlap(d(0), d(2))); // overlaps r1 [1,3)
        assertFalse(tree.hasOverlap(d(20), d(21))); // disjoint from everything
    }

    @Test
    void adjacentHalfOpenIntervalsDoNotOverlap() {
        IntervalTree<String> tree = new IntervalTree<>();
        tree.insert(d(0), d(2), "r1", "r1");

        assertFalse(tree.hasOverlap(d(2), d(4))); // starts exactly when r1 ends
        assertFalse(tree.hasOverlap(d(-2), d(0))); // ends exactly when r1 starts
        assertTrue(tree.hasOverlap(d(1), d(3)));
    }

    @Test
    void findOverlappingReturnsAllMatches() {
        IntervalTree<String> tree = new IntervalTree<>();
        tree.insert(d(0), d(5), "a", "A");
        tree.insert(d(1), d(3), "b", "B");
        tree.insert(d(10), d(12), "c", "C");

        List<String> matches = tree.findOverlapping(d(2), d(4));
        assertEquals(2, matches.size());
        assertTrue(matches.contains("A"));
        assertTrue(matches.contains("B"));
    }

    @Test
    void removeMakesIntervalNoLongerDetected() {
        IntervalTree<String> tree = new IntervalTree<>();
        tree.insert(d(1), d(3), "r1", "r1");
        tree.insert(d(1), d(4), "r1b", "r1b"); // same start, different id

        assertTrue(tree.hasOverlap(d(2), d(2).plusHours(1)));

        tree.remove(d(1), "r1");
        assertEquals(1, tree.size());
        // r1b [1,4) still overlaps day 2
        assertTrue(tree.hasOverlap(d(2), d(2).plusHours(1)));

        tree.remove(d(1), "r1b");
        assertEquals(0, tree.size());
        assertFalse(tree.hasOverlap(d(2), d(2).plusHours(1)));
    }

    @Test
    void removingAbsentIntervalIsNoOp() {
        IntervalTree<String> tree = new IntervalTree<>();
        tree.insert(d(1), d(3), "r1", "r1");

        tree.remove(d(1), "does-not-exist");
        assertEquals(1, tree.size());

        tree.remove(d(99), "nope");
        assertEquals(1, tree.size());
    }

    @Test
    void rejectsNonPositiveInterval() {
        IntervalTree<String> tree = new IntervalTree<>();
        assertThrows(IllegalArgumentException.class, () -> tree.insert(d(1), d(1), "x", "x"));
        assertThrows(IllegalArgumentException.class, () -> tree.insert(d(2), d(1), "x", "x"));
    }

    @Test
    void duplicateIdAtSameStartIsRejected() {
        IntervalTree<String> tree = new IntervalTree<>();
        tree.insert(d(1), d(2), "dup", "a");
        assertThrows(IllegalStateException.class, () -> tree.insert(d(1), d(3), "dup", "b"));
    }

    /**
     * Randomized cross-check: for many random insert/remove/query sequences, the tree's
     * overlap answer must match a brute-force linear scan over the same live intervals.
     * This is what actually proves the AVL rotations and maxEnd augmentation stay correct
     * under arbitrary mutation, rather than just the hand-picked scenarios above.
     */
    @Test
    void matchesBruteForceOverlapAcrossRandomOperations() {
        Random random = new Random(42);
        IntervalTree<Integer> tree = new IntervalTree<>();
        List<int[]> live = new ArrayList<>(); // [start, end, id]
        int nextId = 0;

        for (int iteration = 0; iteration < 5000; iteration++) {
            int op = random.nextInt(3);
            if (op == 0 || live.isEmpty()) {
                int start = random.nextInt(200);
                int length = 1 + random.nextInt(10);
                int end = start + length;
                int id = nextId++;
                tree.insert(d(start), d(end), String.valueOf(id), id);
                live.add(new int[] {start, end, id});
            } else if (op == 1) {
                int idx = random.nextInt(live.size());
                int[] removedInterval = live.remove(idx);
                tree.remove(d(removedInterval[0]), String.valueOf(removedInterval[2]));
            } else {
                int qStart = random.nextInt(200);
                int qEnd = qStart + 1 + random.nextInt(10);
                boolean expected = bruteForceOverlap(live, qStart, qEnd);
                boolean actual = tree.hasOverlap(d(qStart), d(qEnd));
                assertEquals(expected, actual,
                        "mismatch at iteration " + iteration + " for query [" + qStart + "," + qEnd + ")");
            }
        }

        assertEquals(live.size(), tree.size());
    }

    private boolean bruteForceOverlap(List<int[]> live, int qStart, int qEnd) {
        for (int[] interval : live) {
            if (interval[0] < qEnd && qStart < interval[1]) {
                return true;
            }
        }
        return false;
    }
}
