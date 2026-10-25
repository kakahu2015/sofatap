package com.chekusu.sofatap.tv.input;

import java.util.List;

/** Selection uses geometry only. No text, label, or app content is copied into this model. */
public final class NodeHitPolicy {
    public static final int NONE = -1;
    public static final int BLOCKED = -2;
    public static final int AMBIGUOUS = -3;

    public static final class Region {
        public final int left, top, right, bottom, depth, parent;
        public final boolean actionable, blocked;
        public Region(int left, int top, int right, int bottom, int depth, int parent,
                      boolean actionable, boolean blocked) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.depth = depth; this.parent = parent; this.actionable = actionable; this.blocked = blocked;
        }
        boolean contains(double x, double y) {
            return right > left && bottom > top && x >= left && x < right && y >= top && y < bottom;
        }
        long area() { return ((long) right - left) * ((long) bottom - top); }
    }

    /** A sensitive hit blocks any enclosing clickable parent; it must not click through. */
    public static int pick(List<Region> regions, double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return NONE;
        int best = NONE;
        boolean ambiguous = false;
        for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            if (!region.contains(x, y)) continue;
            if (region.blocked) return BLOCKED;
            if (!region.actionable) continue;
            if (best != NONE && !ancestor(regions, best, i) && !ancestor(regions, i, best)) {
                ambiguous = true;
            }
            if (best == NONE || region.depth > regions.get(best).depth
                    || (region.depth == regions.get(best).depth && region.area() < regions.get(best).area())) {
                best = i;
            }
        }
        return ambiguous ? AMBIGUOUS : best;
    }

    private static boolean ancestor(List<Region> regions, int possibleAncestor, int node) {
        for (int attempts = 0; attempts < regions.size(); attempts++) {
            if (node < 0 || node >= regions.size()) return false;
            node = regions.get(node).parent;
            if (node == possibleAncestor) return true;
        }
        return false;
    }
    private NodeHitPolicy() {}
}
