package com.chekusu.sofatap.tv.input;

import org.junit.Test;
import static org.junit.Assert.*;

public class CursorGeometryTest {
    @Test public void reachesAllEdgesWithoutLeavingDisplay() {
        CursorGeometry cursor = new CursorGeometry(1920, 1080);
        assertEquals(959.5, cursor.x(), 0);
        assertEquals(539.5, cursor.y(), 0);
        assertTrue(cursor.move(2048, 2048));
        assertEquals(1919, cursor.x(), 0);
        assertEquals(1079, cursor.y(), 0);
        assertTrue(cursor.move(-2048, -2048));
        assertEquals(0, cursor.x(), 0);
        assertEquals(0, cursor.y(), 0);
    }

    @Test public void rejectsMalformedMovementAtomically() {
        CursorGeometry cursor = new CursorGeometry(100, 100);
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 2049, -2049}) {
            assertFalse(cursor.move(10, value));
            assertFalse(cursor.move(value, 10));
            assertEquals(49.5, cursor.x(), 0);
            assertEquals(49.5, cursor.y(), 0);
        }
    }

    @Test public void resetAndRotationDiscardPreviousPosition() {
        CursorGeometry cursor = new CursorGeometry(1920, 1080);
        cursor.move(300, -300);
        cursor.reset();
        assertEquals(959.5, cursor.x(), 0);
        assertEquals(539.5, cursor.y(), 0);
        cursor.resize(1080, 1920);
        assertEquals(539.5, cursor.x(), 0);
        assertEquals(959.5, cursor.y(), 0);
    }

    @Test public void singlePixelDisplayIsStable() {
        CursorGeometry cursor = new CursorGeometry(1, 1);
        assertTrue(cursor.move(200, -200));
        assertEquals(0, cursor.x(), 0);
        assertEquals(0, cursor.y(), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidDimensions() { new CursorGeometry(0, 1080); }
}
