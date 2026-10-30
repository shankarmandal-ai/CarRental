package com.rental.datastructure;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Augmented AVL tree over half-open intervals [start, end), supporting O(log n)
 * overlap queries. Each node additionally stores maxEnd: the largest end value
 * anywhere in its subtree. During an overlap search, a child subtree is skipped
 * entirely whenever its maxEnd cannot reach the query's start, which is what makes
 * the search sub-linear instead of a full scan of every stored interval.
 *
 * Keyed by (start, id) so multiple intervals sharing the same start are distinct
 * nodes; id breaks ties and identifies which stored interval to remove.
 *
 * Not thread-safe by itself — callers serialize access externally (see
 * RentalService's per-vehicle lock), the same way a plain TreeMap would need to be.
 */
public final class IntervalTree<T> {

    private Node<T> root;
    private int size;

    private static final class Node<T> {
        final LocalDateTime start;
        final LocalDateTime end;
        final String id;
        final T value;
        LocalDateTime maxEnd;
        Node<T> left;
        Node<T> right;
        int height;

        Node(LocalDateTime start, LocalDateTime end, String id, T value) {
            this.start = start;
            this.end = end;
            this.id = id;
            this.value = value;
            this.maxEnd = end;
            this.height = 1;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void insert(LocalDateTime start, LocalDateTime end, String id, T value) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(id, "id");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        root = insert(root, start, end, id, value);
        size++;
    }

    /** Removes the interval previously inserted with this exact (start, id). No-op if absent. */
    public void remove(LocalDateTime start, String id) {
        removed = false;
        root = remove(root, start, id);
        if (removed) {
            size--;
        }
    }

    private boolean removed;

    /** True if any stored interval overlaps the half-open [start, end). */
    public boolean hasOverlap(LocalDateTime start, LocalDateTime end) {
        return hasOverlap(root, start, end);
    }

    /** All stored values whose interval overlaps the half-open [start, end). */
    public List<T> findOverlapping(LocalDateTime start, LocalDateTime end) {
        List<T> result = new ArrayList<>();
        collectOverlapping(root, start, end, result);
        return result;
    }

    // ---- insert ----

    private Node<T> insert(Node<T> node, LocalDateTime start, LocalDateTime end, String id, T value) {
        if (node == null) {
            return new Node<>(start, end, id, value);
        }
        int cmp = compareKey(start, id, node.start, node.id);
        if (cmp < 0) {
            node.left = insert(node.left, start, end, id, value);
        } else if (cmp > 0) {
            node.right = insert(node.right, start, end, id, value);
        } else {
            throw new IllegalStateException("Duplicate interval id inserted: " + id);
        }
        return rebalance(node);
    }

    // ---- remove ----

    private Node<T> remove(Node<T> node, LocalDateTime start, String id) {
        if (node == null) {
            return null;
        }
        int cmp = start.compareTo(node.start);
        if (cmp < 0) {
            node.left = remove(node.left, start, id);
            return rebalance(node);
        }
        if (cmp > 0) {
            node.right = remove(node.right, start, id);
            return rebalance(node);
        }
        if (!node.id.equals(id)) {
            // Same start, different id: the target (if present) could be in either subtree
            // since id only breaks ties among equal starts. Try left first, then right.
            Node<T> newLeft = remove(node.left, start, id);
            if (removed) {
                node.left = newLeft;
                return rebalance(node);
            }
            node.right = remove(node.right, start, id);
            return rebalance(node);
        }
        // Found the exact (start, id) node to remove.
        removed = true;
        if (node.left == null) {
            return node.right;
        }
        if (node.right == null) {
            return node.left;
        }
        Node<T> successor = minNode(node.right);
        Node<T> replacement = new Node<>(successor.start, successor.end, successor.id, successor.value);
        replacement.left = node.left;
        boolean savedRemoved = removed;
        replacement.right = remove(node.right, successor.start, successor.id);
        removed = savedRemoved;
        return rebalance(replacement);
    }

    private Node<T> minNode(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    // ---- overlap search ----

    private boolean hasOverlap(Node<T> node, LocalDateTime start, LocalDateTime end) {
        if (node == null) {
            return false;
        }
        if (node.start.isBefore(end) && start.isBefore(node.end)) {
            return true;
        }
        // Prune: nothing in a subtree can overlap if its maxEnd doesn't reach the query start.
        if (node.left != null && !node.left.maxEnd.isAfter(start)) {
            // left subtree cannot overlap; only right remains possible
            return hasOverlap(node.right, start, end);
        }
        if (hasOverlap(node.left, start, end)) {
            return true;
        }
        if (node.right != null && !node.right.maxEnd.isAfter(start)) {
            return false;
        }
        return hasOverlap(node.right, start, end);
    }

    private void collectOverlapping(Node<T> node, LocalDateTime start, LocalDateTime end, List<T> result) {
        if (node == null) {
            return;
        }
        if (node.left != null && node.left.maxEnd.isAfter(start)) {
            collectOverlapping(node.left, start, end, result);
        }
        if (node.start.isBefore(end) && start.isBefore(node.end)) {
            result.add(node.value);
        }
        if (node.right != null && node.right.maxEnd.isAfter(start)) {
            collectOverlapping(node.right, start, end, result);
        }
    }

    // ---- AVL balancing + maxEnd maintenance ----

    private Node<T> rebalance(Node<T> node) {
        if (node == null) {
            return null;
        }
        updateAugmentation(node);

        int balance = height(node.left) - height(node.right);
        if (balance > 1) {
            if (height(node.left.left) < height(node.left.right)) {
                node.left = rotateLeft(node.left);
            }
            return rotateRight(node);
        }
        if (balance < -1) {
            if (height(node.right.right) < height(node.right.left)) {
                node.right = rotateRight(node.right);
            }
            return rotateLeft(node);
        }
        return node;
    }

    private Node<T> rotateRight(Node<T> y) {
        Node<T> x = y.left;
        y.left = x.right;
        x.right = y;
        updateAugmentation(y);
        updateAugmentation(x);
        return x;
    }

    private Node<T> rotateLeft(Node<T> x) {
        Node<T> y = x.right;
        x.right = y.left;
        y.left = x;
        updateAugmentation(x);
        updateAugmentation(y);
        return y;
    }

    private void updateAugmentation(Node<T> node) {
        node.height = 1 + Math.max(height(node.left), height(node.right));
        LocalDateTime maxEnd = node.end;
        if (node.left != null && node.left.maxEnd.isAfter(maxEnd)) {
            maxEnd = node.left.maxEnd;
        }
        if (node.right != null && node.right.maxEnd.isAfter(maxEnd)) {
            maxEnd = node.right.maxEnd;
        }
        node.maxEnd = maxEnd;
    }

    private int height(Node<T> node) {
        return node == null ? 0 : node.height;
    }

    private int compareKey(LocalDateTime start1, String id1, LocalDateTime start2, String id2) {
        int cmp = start1.compareTo(start2);
        if (cmp != 0) {
            return cmp;
        }
        return id1.compareTo(id2);
    }
}
