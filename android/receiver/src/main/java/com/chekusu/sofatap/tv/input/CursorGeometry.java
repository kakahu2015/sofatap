package com.chekusu.sofatap.tv.input;

/** Pure geometry: coordinates never leave the display and non-finite deltas are rejected. */
public final class CursorGeometry {
    public static final double MAX_DELTA = 2048;
    private double x;
    private double y;
    private int width;
    private int height;

    public CursorGeometry(int width, int height) { resize(width, height); }

    public void resize(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid display size");
        this.width = width;
        this.height = height;
        reset();
    }

    public void reset() { x = (width - 1) / 2.0; y = (height - 1) / 2.0; }

    public boolean move(double dx, double dy) {
        if (!validDelta(dx) || !validDelta(dy)) return false;
        x = Math.max(0, Math.min(width - 1, x + dx));
        y = Math.max(0, Math.min(height - 1, y + dy));
        return true;
    }

    public static boolean validDelta(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && Math.abs(value) <= MAX_DELTA;
    }

    public double x() { return x; }
    public double y() { return y; }
    public int width() { return width; }
    public int height() { return height; }
}
