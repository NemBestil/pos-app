package com.nembestil.pos3.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Per-tablet terminal availability. UDP observations only supply candidate addresses. */
final class PaymentTerminalReachability {
    static final long CHECK_INTERVAL_MS = 60_000;
    static final long RETRY_DELAY_MS = 10_000;
    static final int TCP_PORT = 8080;
    static final int TCP_TIMEOUT_MS = 10_000;
    private static final int MAX_ATTEMPTS = 4;

    interface Probe {
        boolean verifyIdentity(Target target);
        boolean connect(Target target);
        void onError(Target target, Exception error);
    }

    static final class Target {
        final String terminalId;
        final String ipAddress;
        final int port;

        Target(String terminalId, String ipAddress, int port) {
            this.terminalId = terminalId;
            this.ipAddress = ipAddress;
            this.port = port;
        }

        boolean sameEndpoint(Target other) {
            return terminalId.equals(other.terminalId) && ipAddress.equals(other.ipAddress) && port == other.port;
        }
    }

    static final class AvailableTerminal {
        final Target target;
        final long lastSeenAtMs;

        AvailableTerminal(Target target, long lastSeenAtMs) {
            this.target = target;
            this.lastSeenAtMs = lastSeenAtMs;
        }
    }

    private static final class State {
        final Target target;
        boolean identityVerified;
        AvailableTerminal available;
        ScheduledFuture<?> scheduled;
        int attempts;
        long cycleStartedAt;

        State(Target target) {
            this.target = target;
        }
    }

    private final Map<String, State> states = new HashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Executor workers;
    private final Probe probe;
    private final Runnable onObservation;
    private final LongSupplier elapsedTime;
    private final LongSupplier wallTime;
    private boolean closed;

    PaymentTerminalReachability(ScheduledExecutorService scheduler, Executor workers, Probe probe,
        Runnable onObservation, LongSupplier elapsedTime, LongSupplier wallTime) {
        this.scheduler = scheduler;
        this.workers = workers;
        this.probe = probe;
        this.onObservation = onObservation;
        this.elapsedTime = elapsedTime;
        this.wallTime = wallTime;
    }

    synchronized void updateTargets(List<Target> targets) {
        if (closed) return;
        for (State state : new ArrayList<>(states.values())) {
            if (targets.stream().noneMatch(target -> target.sameEndpoint(state.target))) {
                remove(state);
            }
        }
        for (Target target : targets) {
            if (states.containsKey(target.terminalId)) continue;
            State state = new State(target);
            states.put(target.terminalId, state);
            schedule(state, 0);
        }
    }

    synchronized void reset() {
        for (State state : new ArrayList<>(states.values())) remove(state);
        onObservation.run();
    }

    synchronized void close() {
        closed = true;
        reset();
    }

    synchronized List<AvailableTerminal> getAvailable() {
        List<AvailableTerminal> available = new ArrayList<>();
        for (State state : states.values()) {
            if (state.available != null) available.add(state.available);
        }
        return available;
    }

    synchronized AvailableTerminal getAvailable(String terminalId) {
        State state = states.get(terminalId);
        return state == null ? null : state.available;
    }

    private void remove(State state) {
        states.remove(state.target.terminalId, state);
        if (state.scheduled != null) state.scheduled.cancel(false);
    }

    private void schedule(State state, long delayMs) {
        state.scheduled = scheduler.schedule(() -> workers.execute(() -> check(state)), delayMs, TimeUnit.MILLISECONDS);
    }

    private void check(State state) {
        boolean needsIdentity;
        synchronized (this) {
            if (states.get(state.target.terminalId) != state) return;
            if (state.attempts == 0) state.cycleStartedAt = elapsedTime.getAsLong();
            needsIdentity = !state.identityVerified;
        }
        boolean verified = !needsIdentity;
        boolean connected = false;
        try {
            if (needsIdentity) verified = probe.verifyIdentity(state.target);
            if (verified) connected = probe.connect(state.target);
        } catch (Exception error) {
            probe.onError(state.target, error);
        }
        synchronized (this) {
            // Configuration/network changes replace the state; old I/O must never restore availability.
            if (states.get(state.target.terminalId) != state) return;
            state.identityVerified = connected;
            if (connected) {
                state.available = new AvailableTerminal(state.target, wallTime.getAsLong());
                state.attempts = 0;
                schedule(state, Math.max(0, state.cycleStartedAt + CHECK_INTERVAL_MS - elapsedTime.getAsLong()));
                onObservation.run();
            } else if (++state.attempts < MAX_ATTEMPTS) {
                // Keep the last successful availability while the retry sequence is pending.
                // Every failed TCP check invalidates identity before the next attempt.
                schedule(state, RETRY_DELAY_MS);
            } else {
                state.available = null;
                state.attempts = 0;
                schedule(state, CHECK_INTERVAL_MS);
                onObservation.run();
            }
        }
    }
}
