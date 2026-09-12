package ru.big.town.anative;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Typed, process-wide driver-door state source. It contains no CAN transport knowledge. */
final class DriverDoorStateController {
    enum Source {
        LIVE,
        SNAPSHOT,
        REPLAY
    }

    static final class State {
        final int frontLeft;
        final int frontRight;
        final int rearLeft;
        final int rearRight;
        final Source source;

        State(int frontLeft, Source source) {
            this(frontLeft, -1, -1, -1, source);
        }

        State(int frontLeft, int frontRight, int rearLeft, int rearRight, Source source) {
            this.frontLeft = frontLeft;
            this.frontRight = frontRight;
            this.rearLeft = rearLeft;
            this.rearRight = rearRight;
            this.source = source;
        }

        boolean isLive() {
            return source == Source.LIVE;
        }
    }

    interface Listener {
        void onDriverDoorChanged(State state);
    }

    private final Handler serialHandler;
    private final List<Registration> registrations = new ArrayList<>();
    private volatile int currentFrontLeft = -1;
    private volatile int currentFrontRight = -1;
    private volatile int currentRearLeft = -1;
    private volatile int currentRearRight = -1;

    DriverDoorStateController(Handler serialHandler) {
        this.serialHandler = serialHandler;
    }

    Subscription subscribe(Handler deliveryHandler, Listener listener) {
        if (deliveryHandler == null || listener == null) {
            throw new IllegalArgumentException("deliveryHandler/listener required");
        }
        Registration registration = new Registration(deliveryHandler, listener);
        serialHandler.post(() -> {
            if (!registration.active.get()) return;
            registrations.add(registration);
            int snapshotFrontLeft = currentFrontLeft;
            int snapshotFrontRight = currentFrontRight;
            int snapshotRearLeft = currentRearLeft;
            int snapshotRearRight = currentRearRight;
            if (snapshotFrontLeft >= 0 || snapshotFrontRight >= 0
                    || snapshotRearLeft >= 0 || snapshotRearRight >= 0) {
                registration.deliver(new State(snapshotFrontLeft, snapshotFrontRight,
                        snapshotRearLeft, snapshotRearRight, Source.REPLAY));
            }
        });
        return new Subscription(this, registration);
    }

    int currentFrontLeft() {
        return currentFrontLeft;
    }

    void reset() {
        currentFrontLeft = -1;
        currentFrontRight = -1;
        currentRearLeft = -1;
        currentRearRight = -1;
    }

    void accept(int frontLeft, Source source) {
        accept(frontLeft, -1, -1, -1, source);
    }

    void accept(int frontLeft, int frontRight, int rearLeft, int rearRight, Source source) {
        if (frontLeft < 0 && frontRight < 0 && rearLeft < 0 && rearRight < 0) return;
        currentFrontLeft = frontLeft;
        currentFrontRight = frontRight;
        currentRearLeft = rearLeft;
        currentRearRight = rearRight;
        State state = new State(frontLeft, frontRight, rearLeft, rearRight, source);
        for (Registration registration : registrations) registration.deliver(state);
    }

    private void remove(Registration registration) {
        if (!registration.active.compareAndSet(true, false)) return;
        serialHandler.post(() -> registrations.remove(registration));
    }

    static final class Subscription implements AutoCloseable {
        private final DriverDoorStateController owner;
        private final Registration registration;

        Subscription(DriverDoorStateController owner, Registration registration) {
            this.owner = owner;
            this.registration = registration;
        }

        @Override
        public void close() {
            owner.remove(registration);
        }
    }

    private static final class Registration {
        final Handler deliveryHandler;
        final Listener listener;
        final AtomicBoolean active = new AtomicBoolean(true);

        Registration(Handler deliveryHandler, Listener listener) {
            this.deliveryHandler = deliveryHandler;
            this.listener = listener;
        }

        void deliver(State state) {
            deliveryHandler.post(() -> {
                if (active.get()) listener.onDriverDoorChanged(state);
            });
        }
    }
}
