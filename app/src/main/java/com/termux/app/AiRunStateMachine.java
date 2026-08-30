package com.termux.app;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/** Validates lifecycle transitions for durable AI runs. */
public final class AiRunStateMachine {

    public enum State {
        CREATED,
        STARTING,
        CONNECTING,
        AUTH_REQUIRED,
        RUNNING,
        WAITING_APPROVAL,
        WAITING_INPUT,
        INTERRUPTING,
        DISCONNECTED,
        RECONNECTING,
        COMPLETED,
        FAILED,
        CANCELED
    }

    private static final Map<State, EnumSet<State>> TRANSITIONS = new HashMap<>();

    static {
        TRANSITIONS.put(State.CREATED, EnumSet.of(State.STARTING, State.CANCELED));
        TRANSITIONS.put(State.STARTING, EnumSet.of(State.CONNECTING, State.AUTH_REQUIRED, State.FAILED, State.CANCELED));
        TRANSITIONS.put(State.CONNECTING, EnumSet.of(State.RUNNING, State.AUTH_REQUIRED, State.FAILED, State.DISCONNECTED, State.CANCELED));
        TRANSITIONS.put(State.AUTH_REQUIRED, EnumSet.of(State.CONNECTING, State.FAILED, State.CANCELED));
        TRANSITIONS.put(State.RUNNING, EnumSet.of(State.WAITING_APPROVAL, State.WAITING_INPUT,
            State.INTERRUPTING, State.DISCONNECTED, State.COMPLETED, State.FAILED, State.CANCELED));
        TRANSITIONS.put(State.WAITING_APPROVAL, EnumSet.of(State.RUNNING, State.INTERRUPTING, State.COMPLETED,
            State.DISCONNECTED, State.FAILED, State.CANCELED));
        TRANSITIONS.put(State.WAITING_INPUT, EnumSet.of(State.RUNNING, State.INTERRUPTING, State.COMPLETED,
            State.DISCONNECTED, State.FAILED, State.CANCELED));
        TRANSITIONS.put(State.INTERRUPTING, EnumSet.of(State.COMPLETED, State.DISCONNECTED, State.FAILED, State.CANCELED));
        TRANSITIONS.put(State.DISCONNECTED, EnumSet.of(State.RECONNECTING, State.FAILED, State.CANCELED));
        TRANSITIONS.put(State.RECONNECTING, EnumSet.of(State.CONNECTING, State.AUTH_REQUIRED, State.RUNNING,
            State.FAILED, State.CANCELED));
        TRANSITIONS.put(State.COMPLETED, EnumSet.of(State.RUNNING, State.CANCELED));
        TRANSITIONS.put(State.FAILED, EnumSet.of(State.RECONNECTING, State.CANCELED));
        TRANSITIONS.put(State.CANCELED, EnumSet.noneOf(State.class));
    }

    private State mState = State.CREATED;

    public synchronized State getState() {
        return mState;
    }

    public synchronized void transition(State nextState) {
        if (nextState == null) throw new IllegalArgumentException("nextState must not be null");
        if (mState == nextState) return;

        EnumSet<State> allowed = TRANSITIONS.get(mState);
        if (allowed == null || !allowed.contains(nextState)) {
            throw new IllegalStateException("Invalid AI run transition: " + mState + " -> " + nextState);
        }
        mState = nextState;
    }
}
