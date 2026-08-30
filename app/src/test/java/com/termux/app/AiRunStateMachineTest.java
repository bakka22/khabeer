package com.termux.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AiRunStateMachineTest {

    @Test
    public void shouldAllowApprovalAndCompletionLifecycle() {
        AiRunStateMachine machine = new AiRunStateMachine();

        machine.transition(AiRunStateMachine.State.STARTING);
        machine.transition(AiRunStateMachine.State.CONNECTING);
        machine.transition(AiRunStateMachine.State.RUNNING);
        machine.transition(AiRunStateMachine.State.WAITING_APPROVAL);
        machine.transition(AiRunStateMachine.State.RUNNING);
        machine.transition(AiRunStateMachine.State.COMPLETED);

        assertEquals(AiRunStateMachine.State.COMPLETED, machine.getState());
    }

    @Test
    public void shouldAllowReconnectAfterTransportDisconnect() {
        AiRunStateMachine machine = new AiRunStateMachine();

        machine.transition(AiRunStateMachine.State.STARTING);
        machine.transition(AiRunStateMachine.State.CONNECTING);
        machine.transition(AiRunStateMachine.State.RUNNING);
        machine.transition(AiRunStateMachine.State.DISCONNECTED);
        machine.transition(AiRunStateMachine.State.RECONNECTING);
        machine.transition(AiRunStateMachine.State.CONNECTING);
        machine.transition(AiRunStateMachine.State.RUNNING);

        assertEquals(AiRunStateMachine.State.RUNNING, machine.getState());
    }

    @Test
    public void shouldAllowInterruptWhileWaitingForUser() {
        AiRunStateMachine machine = new AiRunStateMachine();

        machine.transition(AiRunStateMachine.State.STARTING);
        machine.transition(AiRunStateMachine.State.CONNECTING);
        machine.transition(AiRunStateMachine.State.RUNNING);
        machine.transition(AiRunStateMachine.State.WAITING_INPUT);
        machine.transition(AiRunStateMachine.State.INTERRUPTING);
        machine.transition(AiRunStateMachine.State.CANCELED);

        assertEquals(AiRunStateMachine.State.CANCELED, machine.getState());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectCompletionBeforeStartup() {
        new AiRunStateMachine().transition(AiRunStateMachine.State.COMPLETED);
    }
}
