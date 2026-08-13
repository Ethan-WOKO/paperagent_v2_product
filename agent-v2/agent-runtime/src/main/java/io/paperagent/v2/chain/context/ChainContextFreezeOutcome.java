package io.paperagent.v2.chain.context;

import java.util.Objects;

public sealed interface ChainContextFreezeOutcome
        permits ChainContextFreezeOutcome.Complete, ChainContextFreezeOutcome.InputBlocked,
        ChainContextFreezeOutcome.BuildBlocked {
    ChainFrozenContext context();

    record Complete(ChainFrozenContext context) implements ChainContextFreezeOutcome {
        public Complete {
            Objects.requireNonNull(context, "context");
        }
    }

    record InputBlocked(ChainFrozenContext context, String errorCode, int inputCharacters)
            implements ChainContextFreezeOutcome {
        public InputBlocked {
            Objects.requireNonNull(context, "context");
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("errorCode must not be blank");
            }
            if (inputCharacters < 1) {
                throw new IllegalArgumentException("inputCharacters must be positive");
            }
        }
    }

    record BuildBlocked(
            io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord buildingRevision,
            io.paperagent.v2.chain.ChainPersistenceRecords.ContextBuildFailureRecord failure)
            implements ChainContextFreezeOutcome {
        public BuildBlocked {
            Objects.requireNonNull(buildingRevision, "buildingRevision");
            Objects.requireNonNull(failure, "failure");
            if (buildingRevision.status()
                    != io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING
                    || !buildingRevision.taskId().equals(failure.taskId())
                    || !buildingRevision.contextRevisionId().equals(
                    failure.contextRevisionId())) {
                throw new IllegalArgumentException(
                        "Context build failure must bind its BUILDING revision");
            }
        }

        @Override
        public ChainFrozenContext context() {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REVISION_NOT_RECOVERABLE,
                    "a failed BUILDING revision has no frozen model Context");
        }
    }
}
