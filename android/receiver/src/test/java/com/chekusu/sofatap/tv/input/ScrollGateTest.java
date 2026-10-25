package com.chekusu.sofatap.tv.input;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScrollGateTest {
    @Test public void accumulatesSmallMovementAndChoosesDominantAxis() {
        ScrollGate gate = new ScrollGate();
        assertNull(gate.offer(4, 8, 0));
        assertNull(gate.offer(4, 8, 16));
        ScrollGate.Step step = gate.offer(4, 8, 32);
        assertNotNull(step);
        assertEquals(0, step.x, 0);
        assertEquals(1, step.y, 0);
    }

    @Test public void frameRateBurstNeverProducesMoreThanOneStepPerInterval() {
        ScrollGate gate = new ScrollGate();
        int steps = 0;
        for (int time = 0; time < 1000; time += 10) {
            if (gate.offer(0, 100, time) != null) steps++;
        }
        assertEquals(9, steps);
    }

    @Test public void oldMovementDoesNotReplayAfterIdle() {
        ScrollGate gate = new ScrollGate();
        assertNotNull(gate.offer(0, 24, 0));
        assertNull(gate.offer(0, 100, 20));
        assertNull(gate.offer(0, 1, 321));
    }

    @Test public void disconnectDropsAllAccumulatedMovementAndRateState() {
        ScrollGate gate = new ScrollGate();
        assertNotNull(gate.offer(0, 24, 0));
        assertNull(gate.offer(0, 100, 20));
        gate.reset();
        assertNull(gate.offer(0, 1, 30));
        assertNotNull(gate.offer(0, 24, 40));
    }

    @Test public void reversalDoesNotSpendOldOppositeDirectionMovement() {
        ScrollGate gate = new ScrollGate();
        assertNull(gate.offer(20, 0, 0));
        assertNull(gate.offer(-10, 0, 10));
        ScrollGate.Step step = gate.offer(-14, 0, 20);
        assertNotNull(step);
        assertEquals(-1, step.x, 0);
        assertEquals(0, step.y, 0);
    }

    @Test public void invalidPacketsDoNotChangePendingMovement() {
        ScrollGate gate = new ScrollGate();
        assertNull(gate.offer(0, 23, 0));
        assertNull(gate.offer(Double.NaN, 100, 10));
        assertNull(gate.offer(0, 100, -1));
        assertNotNull(gate.offer(0, 1, 20));
    }
}
