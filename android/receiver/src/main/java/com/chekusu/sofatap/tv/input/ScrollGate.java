package com.chekusu.sofatap.tv.input;

/** Converts touch-surface deltas to bounded accessibility scroll steps, with no delayed replay. */
public final class ScrollGate {
    public static final long INTERVAL_MS = 120;
    public static final long IDLE_RESET_MS = 300;
    public static final double THRESHOLD = 24;
    private double accumulatedX;
    private double accumulatedY;
    private long lastEvent = -1;
    private long lastStep = -1;

    public static final class Step {
        public final double x, y;
        private Step(double x, double y) { this.x = x; this.y = y; }
    }

    public Step offer(double dx, double dy, long now) {
        if (now < 0 || !CursorGeometry.validDelta(dx) || !CursorGeometry.validDelta(dy)) return null;
        if (lastEvent >= 0 && (now < lastEvent || now - lastEvent > IDLE_RESET_MS)) reset();
        lastEvent = now;
        if (dx * accumulatedX < 0) accumulatedX = 0;
        if (dy * accumulatedY < 0) accumulatedY = 0;
        accumulatedX = bounded(accumulatedX + dx);
        accumulatedY = bounded(accumulatedY + dy);
        if (Math.max(Math.abs(accumulatedX), Math.abs(accumulatedY)) < THRESHOLD) return null;
        if (lastStep >= 0 && now - lastStep < INTERVAL_MS) return null;
        Step result = Math.abs(accumulatedX) > Math.abs(accumulatedY)
                ? new Step(Math.signum(accumulatedX), 0) : new Step(0, Math.signum(accumulatedY));
        accumulatedX = 0;
        accumulatedY = 0;
        lastStep = now;
        return result;
    }

    public void reset() {
        accumulatedX = 0;
        accumulatedY = 0;
        lastEvent = -1;
        lastStep = -1;
    }

    private static double bounded(double value) {
        return Math.max(-THRESHOLD * 2, Math.min(THRESHOLD * 2, value));
    }
}
