package com.originlore;

/** Pure range arithmetic shared by item migration and runtime sampling. */
public final class RandomRanges {
    private RandomRanges() {}

    public static double position(double value, double min, double max) {
        if (!Double.isFinite(value)) return 0.5;
        return min == max ? 0.5 : Math.clamp((value - min) / (max - min), 0, 1);
    }

    public static double map(double position, double min, double max) {
        if (!Double.isFinite(position) || !Double.isFinite(min) || !Double.isFinite(max)
                || min > max || !Double.isFinite(max - min)) throw new IllegalArgumentException("invalid random range");
        return min + Math.clamp(position, 0, 1) * (max - min);
    }

    public static int mapDamage(int damage, int oldMaximum, int newMaximum) {
        if (oldMaximum <= 0 || newMaximum <= 0) throw new IllegalArgumentException("durability maximum must be positive");
        double remainingRatio = Math.clamp((oldMaximum - (double) damage) / oldMaximum, 0, 1);
        long remaining = Math.round(remainingRatio * newMaximum);
        if (remainingRatio > 0) remaining = Math.max(1, remaining);
        return newMaximum - (int) Math.clamp(remaining, 0, newMaximum);
    }
}
