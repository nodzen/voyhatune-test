package ru.big.town.anative;

/** Latest-wins generation fence shared by physical, widget and cluster launches. */
final class LaunchGeneration {
    private long generation;
    private boolean closed;

    synchronized long next() {
        return closed ? -1L : ++generation;
    }

    synchronized boolean accepts(long value) {
        return !closed && value > 0L && value == generation;
    }

    synchronized void cancel() {
        if (!closed) generation++;
    }

    synchronized void close() {
        closed = true;
        generation++;
    }
}
