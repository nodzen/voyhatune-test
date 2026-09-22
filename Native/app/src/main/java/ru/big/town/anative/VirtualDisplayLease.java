package ru.big.town.anative;

import android.os.Handler;
import android.os.Looper;

/** Enforces one visible VoyahTune VirtualDisplay outside the split host. */
final class VirtualDisplayLease {
    interface Owner { void releaseForSuccessor(Runnable completion); }
    private static Owner owner;
    private static long generation;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private VirtualDisplayLease() {}

    static void acquire(Owner next, Runnable acquired) {
        Runnable operation = () -> {
            Owner previous;
            long ticket;
            synchronized (VirtualDisplayLease.class) {
                previous = owner;
                if (previous == next) {
                    if (acquired != null) acquired.run();
                    return;
                }
                owner = next;
                ticket = ++generation;
            }
            Runnable completeIfLatest = () -> {
                synchronized (VirtualDisplayLease.class) {
                    if (owner != next || generation != ticket) return;
                }
                if (acquired != null) acquired.run();
            };
            if (previous == null) {
                completeIfLatest.run();
            } else {
                previous.releaseForSuccessor(completeIfLatest);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) operation.run(); else MAIN.post(operation);
    }

    static synchronized void release(Owner current) {
        if (owner == current) {
            owner = null;
            generation++;
        }
    }
}
