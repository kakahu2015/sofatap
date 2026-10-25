package com.chekusu.sofatap.tv.input;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class NodeHitPolicyTest {
    private NodeHitPolicy.Region region(int left, int top, int right, int bottom,
                                      int depth, int parent, boolean action, boolean blocked) {
        return new NodeHitPolicy.Region(left, top, right, bottom, depth, parent, action, blocked);
    }

    @Test public void clicksDeepestAccessibleNodeInsteadOfEnclosingContainer() {
        assertEquals(1, NodeHitPolicy.pick(Arrays.asList(
                region(0, 0, 500, 500, 0, -1, true, false),
                region(50, 50, 200, 200, 1, 0, true, false)), 100, 100));
    }

    @Test public void passiveLabelCanUseEnclosingClickableParent() {
        assertEquals(0, NodeHitPolicy.pick(Arrays.asList(
                region(0, 0, 500, 500, 0, -1, true, false),
                region(50, 50, 200, 200, 1, 0, false, false)), 100, 100));
    }

    @Test public void passwordOrApprovalRegionBlocksItsClickableParent() {
        assertEquals(NodeHitPolicy.BLOCKED, NodeHitPolicy.pick(Arrays.asList(
                region(0, 0, 500, 500, 0, -1, true, false),
                region(50, 50, 200, 200, 1, 0, false, true)), 100, 100));
    }

    @Test public void sensitiveRegionWinsEvenAfterAmbiguousCandidate() {
        assertEquals(NodeHitPolicy.BLOCKED, NodeHitPolicy.pick(Arrays.asList(
                region(0, 0, 500, 500, 0, -1, true, false),
                region(50, 50, 200, 200, 1, -1, true, false),
                region(50, 50, 200, 200, 2, 1, false, true)), 100, 100));
    }

    @Test public void doesNotGuessBetweenOverlappingSiblingButtons() {
        assertEquals(NodeHitPolicy.AMBIGUOUS, NodeHitPolicy.pick(Arrays.asList(
                region(0, 0, 500, 500, 0, -1, false, false),
                region(50, 50, 200, 200, 1, 0, true, false),
                region(75, 75, 250, 250, 1, 0, true, false)), 100, 100));
    }

    @Test public void excludesBottomAndRightEdges() {
        assertEquals(NodeHitPolicy.NONE, NodeHitPolicy.pick(Collections.singletonList(
                region(0, 0, 100, 100, 0, -1, true, false)), 100, 99));
        assertEquals(NodeHitPolicy.NONE, NodeHitPolicy.pick(Collections.singletonList(
                region(0, 0, 100, 100, 0, -1, true, false)), 99, 100));
    }

    @Test public void handlesNoNodesInvalidCoordinatesAndEmptyBounds() {
        assertEquals(NodeHitPolicy.NONE, NodeHitPolicy.pick(Collections.emptyList(), 50, 50));
        assertEquals(NodeHitPolicy.NONE, NodeHitPolicy.pick(Collections.singletonList(
                region(0, 0, 100, 100, 0, -1, true, false)), Double.NaN, 50));
        assertEquals(NodeHitPolicy.NONE, NodeHitPolicy.pick(Collections.singletonList(
                region(10, 10, 10, 10, 0, -1, true, false)), 10, 10));
    }
}
