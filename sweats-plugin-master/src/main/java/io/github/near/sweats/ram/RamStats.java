package io.github.near.sweats.ram;

public record RamStats(long usedBytes, long allocatedBytes, long maxBytes) {

    public static RamStats from(final long totalBytes, final long freeBytes, final long maxBytes) {
        final long allocated = Math.max(0L, totalBytes);
        final long free = Math.min(allocated, Math.max(0L, freeBytes));
        final long maximum = maxBytes > 0L && maxBytes != Long.MAX_VALUE ? maxBytes : 0L;
        return new RamStats(allocated - free, allocated, maximum);
    }

    public boolean hasMaximum() {
        return maxBytes > 0L;
    }

    public double usedPercent() {
        if (!hasMaximum()) {
            return 0.0D;
        }
        return Math.min(100.0D, usedBytes * 100.0D / maxBytes);
    }
}