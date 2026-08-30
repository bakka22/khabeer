package com.termux.app;

import java.util.ArrayList;
import java.util.List;

public final class HermesSessionState {

    public static final class TurnState {
        public Object agent;
        public long startedTs;
        public Object lease;
        public long busyAckTs;
        public Object leaseToken;
        public Integer leaseGeneration;

        public void clear() {
            agent = null;
            startedTs = 0;
            lease = null;
            busyAckTs = 0;
        }
    }

    public static final class ConversationState {
        public String modelOverride;
        public String reasoningOverride;
        public String serviceTierOverride;
        public boolean serviceTierPresent;
        public String lastResolvedModel = "";
        public final List<String> queuedEvents = new ArrayList<>();
        public final List<String> sidecarNotes = new ArrayList<>();
        public String ephemeralPin;
        public String vcLast;

        public void clear() {
            modelOverride = null;
            reasoningOverride = null;
            serviceTierOverride = null;
            serviceTierPresent = false;
            lastResolvedModel = "";
            queuedEvents.clear();
            sidecarNotes.clear();
            ephemeralPin = null;
            vcLast = null;
        }
    }

    public static final class PersistentState {
        public String pendingApproval;
        public boolean updatePromptPending;
        public final List<String> nativeImagePaths = new ArrayList<>();
        public String pendingCommandText;
        public int runGeneration;
        public int hygieneFailureStreak;

        public void bumpGeneration() { runGeneration++; }
    }

    public final TurnState turn = new TurnState();
    public final ConversationState conversation = new ConversationState();
    public final PersistentState persistent = new PersistentState();

    public void clearTurn() { turn.clear(); }
    public void clearConversation() { conversation.clear(); }
}
