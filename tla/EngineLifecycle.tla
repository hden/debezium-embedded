---- MODULE EngineLifecycle ----
EXTENDS Naturals, TLC

(*******************************************************************************
  This is a finite quotient of the wrapper's observation interpreter. It does
  not model Debezium's private state or the unbounded trace. It retains facts
  that change a later wrapper decision: effect starts and outcomes, callbacks,
  terminal outcome, and one abstract batch.

  Each external call has three distinct parts:
    1. a pure interpretation decides whether it may start;
    2. a *-started fact is appended at the wrapper's effect boundary;
    3. a returned or anomaly fact is appended later.
  CAS is an implementation technique for recording step 2. It is not a model
  event or a domain term.
*******************************************************************************)

Phases == {"ready", "starting", "capturing", "stopping", "stopped"}
ConnectorStates == {"none", "started", "stopped"}
PollingStates == {"none", "started", "started-after-shutdown-failure", "stopped"}
CompletionOutcomes == {"none", "succeeded", "failed"}
BatchStates == {"none", "admitted", "handled", "first-acknowledgement-started",
                "first-acknowledged", "acknowledgement-started", "acknowledged",
                "consumer-failed", "acknowledgement-failed"}
Callbacks == {"connector-started", "connector-stopped", "polling-started",
              "polling-stopped", "completion-observed"}
NoCallback == "no-callback"
NoPhase == "no-phase"

VARIABLES phase, submissionStarted, invocationStarted, invocationCancelled,
          connector, polling, stopRequested, completion, terminalAnomaly,
          protocolAnomaly, shutdownRequests, shutdownFailure, batchState,
          invalidCallback, acknowledgementStartedPhase, gracefulConfirmed

Vars == <<phase, submissionStarted, invocationStarted, invocationCancelled,
          connector, polling, stopRequested, completion, terminalAnomaly,
          protocolAnomaly, shutdownRequests, shutdownFailure, batchState,
          invalidCallback, acknowledgementStartedPhase, gracefulConfirmed>>

Init ==
  /\ phase = "ready"
  /\ submissionStarted = FALSE
  /\ invocationStarted = FALSE
  /\ invocationCancelled = FALSE
  /\ connector = "none"
  /\ polling = "none"
  /\ stopRequested = FALSE
  /\ completion = "none"
  /\ terminalAnomaly = FALSE
  /\ protocolAnomaly = FALSE
  /\ shutdownRequests = 0
  /\ shutdownFailure = FALSE
  /\ batchState = "none"
  /\ invalidCallback = NoCallback
  /\ acknowledgementStartedPhase = NoPhase
  /\ gracefulConfirmed = FALSE

RecordInvalidCallback(kind) ==
  /\ kind \in Callbacks
  /\ invalidCallback' = kind
  /\ protocolAnomaly' = TRUE
  /\ terminalAnomaly' = IF completion = "none" THEN TRUE ELSE terminalAnomaly
  /\ phase' = IF phase = "stopped" \/ completion # "none"
              THEN "stopped"
              ELSE "stopping"
  /\ UNCHANGED <<submissionStarted, invocationStarted, invocationCancelled,
                 connector, polling, stopRequested, completion, shutdownRequests,
                 shutdownFailure, batchState, acknowledgementStartedPhase,
                 gracefulConfirmed>>

RequestStart ==
  /\ phase = "ready"
  /\ phase' = "starting"
  /\ UNCHANGED <<submissionStarted, invocationStarted, invocationCancelled,
                 connector, polling, stopRequested, completion, terminalAnomaly,
                 protocolAnomaly, shutdownRequests, shutdownFailure, batchState,
                 invalidCallback, acknowledgementStartedPhase, gracefulConfirmed>>

StartEngineSubmission ==
  /\ phase = "starting"
  /\ ~submissionStarted
  /\ submissionStarted' = TRUE
  /\ UNCHANGED <<phase, invocationStarted, invocationCancelled, connector,
                 polling, stopRequested, completion, terminalAnomaly,
                 protocolAnomaly, shutdownRequests, shutdownFailure, batchState,
                 invalidCallback, acknowledgementStartedPhase, gracefulConfirmed>>

RejectEngineSubmission ==
  /\ submissionStarted
  /\ ~invocationStarted
  /\ completion = "none"
  /\ terminalAnomaly' = IF invocationCancelled THEN terminalAnomaly ELSE TRUE
  /\ phase' = IF invocationCancelled THEN phase ELSE "stopped"
  /\ UNCHANGED <<submissionStarted, invocationStarted, invocationCancelled,
                 connector, polling, stopRequested, completion, protocolAnomaly,
                 shutdownRequests, shutdownFailure, batchState, invalidCallback,
                 acknowledgementStartedPhase, gracefulConfirmed>>

StartEngineInvocation ==
  /\ phase = "starting"
  /\ submissionStarted
  /\ ~stopRequested
  /\ ~terminalAnomaly
  /\ ~invocationStarted
  /\ invocationStarted' = TRUE
  /\ UNCHANGED <<phase, submissionStarted, invocationCancelled, connector,
                 polling, stopRequested, completion, terminalAnomaly,
                 protocolAnomaly, shutdownRequests, shutdownFailure, batchState,
                 invalidCallback, acknowledgementStartedPhase, gracefulConfirmed>>

RequestStop ==
  /\ ~stopRequested
  /\ phase # "stopped"
  /\ stopRequested' = TRUE
  /\ phase' = IF phase = "ready" \/ (phase = "starting" /\ ~invocationStarted)
              THEN "stopped"
              ELSE "stopping"
  /\ invocationCancelled' = (phase = "starting" /\ ~invocationStarted)
  /\ UNCHANGED <<submissionStarted, invocationStarted, connector, polling,
                 completion, terminalAnomaly, protocolAnomaly, shutdownRequests,
                 shutdownFailure, batchState, invalidCallback,
                 acknowledgementStartedPhase, gracefulConfirmed>>

ReceiveConnectorStarted ==
  /\ ~protocolAnomaly
  /\ IF phase = "starting" /\ invocationStarted /\ connector = "none"
     THEN /\ connector' = "started"
          /\ UNCHANGED <<phase, submissionStarted, invocationStarted,
                         invocationCancelled, polling, stopRequested, completion,
                         terminalAnomaly, protocolAnomaly, shutdownRequests,
                         shutdownFailure, batchState, invalidCallback,
                         acknowledgementStartedPhase, gracefulConfirmed>>
     ELSE RecordInvalidCallback("connector-started")

ReceiveConnectorStopped ==
  /\ ~protocolAnomaly
  /\ IF phase \in {"starting", "capturing", "stopping"} /\ connector = "started"
     THEN /\ connector' = "stopped"
          /\ phase' = "stopping"
          /\ UNCHANGED <<submissionStarted, invocationStarted,
                         invocationCancelled, polling, stopRequested, completion,
                         terminalAnomaly, protocolAnomaly, shutdownRequests,
                         shutdownFailure, batchState, invalidCallback,
                         acknowledgementStartedPhase, gracefulConfirmed>>
     ELSE RecordInvalidCallback("connector-stopped")

ReceivePollingStarted ==
  /\ ~protocolAnomaly
  /\ IF (phase = "starting" /\ invocationStarted /\ connector = "started"
          /\ polling = "none")
     THEN /\ polling' = "started"
          /\ phase' = "capturing"
          /\ UNCHANGED <<submissionStarted, invocationStarted,
                         invocationCancelled, connector, stopRequested, completion,
                         terminalAnomaly, protocolAnomaly, shutdownRequests,
                         shutdownFailure, batchState, invalidCallback,
                         acknowledgementStartedPhase, gracefulConfirmed>>
     ELSE IF (phase = "stopping" /\ shutdownFailure /\ invocationStarted
              /\ connector = "started"
              /\ polling = "none")
          THEN /\ polling' = "started-after-shutdown-failure"
               /\ UNCHANGED <<phase, submissionStarted, invocationStarted,
                              invocationCancelled, connector, stopRequested,
                              completion, terminalAnomaly, protocolAnomaly,
                              shutdownRequests, shutdownFailure, batchState,
                              invalidCallback, acknowledgementStartedPhase,
                              gracefulConfirmed>>
     ELSE RecordInvalidCallback("polling-started")

ReceivePollingStopped ==
  /\ ~protocolAnomaly
  /\ IF phase = "capturing" /\ polling = "started"
     THEN /\ polling' = "stopped"
          /\ phase' = "stopping"
          /\ UNCHANGED <<submissionStarted, invocationStarted,
                         invocationCancelled, connector, stopRequested, completion,
                         terminalAnomaly, protocolAnomaly, shutdownRequests,
                         shutdownFailure, batchState, invalidCallback,
                         acknowledgementStartedPhase, gracefulConfirmed>>
     ELSE RecordInvalidCallback("polling-stopped")

ReceiveSuccessfulCompletion ==
  /\ completion = "none"
  /\ IF phase = "stopping"
     THEN /\ completion' = "succeeded"
          /\ phase' = "stopped"
          /\ UNCHANGED <<submissionStarted, invocationStarted,
                         invocationCancelled, connector, polling, stopRequested,
                         terminalAnomaly, protocolAnomaly, shutdownRequests,
                         shutdownFailure, batchState, invalidCallback,
                         acknowledgementStartedPhase, gracefulConfirmed>>
     ELSE /\ completion' = "succeeded"
          /\ invalidCallback' = "completion-observed"
          /\ protocolAnomaly' = TRUE
          /\ terminalAnomaly' = TRUE
          /\ phase' = "stopped"
          /\ UNCHANGED <<submissionStarted, invocationStarted,
                         invocationCancelled, connector, polling, stopRequested,
                         shutdownRequests, shutdownFailure, batchState,
                         acknowledgementStartedPhase, gracefulConfirmed>>

ReceiveFailedCompletion ==
  /\ completion = "none"
  /\ completion' = "failed"
  /\ terminalAnomaly' = TRUE
  /\ protocolAnomaly' = (protocolAnomaly \/ phase # "stopping")
  /\ invalidCallback' = IF phase = "stopping" THEN invalidCallback
                      ELSE "completion-observed"
  /\ phase' = "stopped"
  /\ UNCHANGED <<submissionStarted, invocationStarted, invocationCancelled,
                 connector, polling, stopRequested, shutdownRequests,
                 shutdownFailure, batchState, acknowledgementStartedPhase,
                 gracefulConfirmed>>

StartShutdownRequest ==
  /\ phase = "stopping"
  /\ completion = "none"
  /\ (shutdownRequests = 0 \/
      (shutdownRequests = 1 /\ shutdownFailure
       /\ polling = "started-after-shutdown-failure"))
  /\ shutdownRequests' = shutdownRequests + 1
  /\ UNCHANGED <<phase, submissionStarted, invocationStarted,
                 invocationCancelled, connector, polling, stopRequested,
                 completion, terminalAnomaly, protocolAnomaly, shutdownFailure,
                 batchState, invalidCallback, acknowledgementStartedPhase,
                 gracefulConfirmed>>

RecordShutdownFailure ==
  /\ shutdownRequests > 0
  /\ ~shutdownFailure
  /\ shutdownFailure' = TRUE
  /\ terminalAnomaly' = IF completion = "none" THEN TRUE ELSE terminalAnomaly
  /\ phase' = IF completion = "none" THEN "stopping" ELSE phase
  /\ UNCHANGED <<submissionStarted, invocationStarted, invocationCancelled,
                 connector, polling, stopRequested, completion, protocolAnomaly,
                 shutdownRequests, batchState, invalidCallback,
                 acknowledgementStartedPhase, gracefulConfirmed>>

AdmitBatch ==
  /\ phase = "capturing"
  /\ batchState = "none"
  /\ batchState' = "admitted"
  /\ UNCHANGED <<phase, submissionStarted, invocationStarted,
                 invocationCancelled, connector, polling, stopRequested,
                 completion, terminalAnomaly, protocolAnomaly, shutdownRequests,
                 shutdownFailure, invalidCallback, acknowledgementStartedPhase,
                 gracefulConfirmed>>

HandleBatch ==
  /\ phase = "capturing"
  /\ batchState = "admitted"
  /\ batchState' = "handled"
  /\ UNCHANGED <<phase, submissionStarted, invocationStarted,
                 invocationCancelled, connector, polling, stopRequested,
                 completion, terminalAnomaly, protocolAnomaly, shutdownRequests,
                 shutdownFailure, invalidCallback, acknowledgementStartedPhase,
                 gracefulConfirmed>>

StartAcknowledgement ==
  /\ phase = "capturing"
  /\ batchState \in {"handled", "first-acknowledged"}
  /\ batchState' = IF batchState = "handled"
                  THEN "first-acknowledgement-started"
                  ELSE "acknowledgement-started"
  /\ acknowledgementStartedPhase' = phase
  /\ UNCHANGED <<phase, submissionStarted, invocationStarted,
                 invocationCancelled, connector, polling, stopRequested,
                 completion, terminalAnomaly, protocolAnomaly, shutdownRequests,
                 shutdownFailure, invalidCallback, gracefulConfirmed>>

RecordAcknowledgement ==
  /\ batchState \in {"first-acknowledgement-started", "acknowledgement-started"}
  /\ batchState' = IF batchState = "first-acknowledgement-started"
                  THEN "first-acknowledged"
                  ELSE "acknowledged"
  /\ UNCHANGED <<phase, submissionStarted, invocationStarted,
                 invocationCancelled, connector, polling, stopRequested,
                 completion, terminalAnomaly, protocolAnomaly, shutdownRequests,
                 shutdownFailure, invalidCallback, acknowledgementStartedPhase,
                 gracefulConfirmed>>

RecordConsumerFailure ==
  /\ batchState \in {"admitted", "handled"}
  /\ terminalAnomaly' = IF completion = "none" THEN TRUE ELSE terminalAnomaly
  /\ phase' = IF completion = "none" THEN "stopping" ELSE phase
  /\ batchState' = "consumer-failed"
  /\ UNCHANGED <<submissionStarted, invocationStarted, invocationCancelled,
                 connector, polling, stopRequested, completion, protocolAnomaly,
                 shutdownRequests, shutdownFailure, invalidCallback,
                 acknowledgementStartedPhase, gracefulConfirmed>>

RecordAcknowledgementFailure ==
  /\ batchState \in {"first-acknowledgement-started", "acknowledgement-started"}
  /\ terminalAnomaly' = IF completion = "none" THEN TRUE ELSE terminalAnomaly
  /\ phase' = IF completion = "none" THEN "stopping" ELSE phase
  /\ batchState' = "acknowledgement-failed"
  /\ UNCHANGED <<submissionStarted, invocationStarted, invocationCancelled,
                 connector, polling, stopRequested, completion, protocolAnomaly,
                 shutdownRequests, shutdownFailure, invalidCallback,
                 acknowledgementStartedPhase, gracefulConfirmed>>

ConfirmGracefulCompletion ==
  /\ ~gracefulConfirmed
  /\ phase = "stopped"
  /\ completion = "succeeded"
  /\ ~terminalAnomaly
  /\ batchState \in {"none", "acknowledged"}
  /\ connector \in {"none", "stopped"}
  /\ gracefulConfirmed' = TRUE
  /\ UNCHANGED <<phase, submissionStarted, invocationStarted,
                 invocationCancelled, connector, polling, stopRequested,
                 completion, terminalAnomaly, protocolAnomaly, shutdownRequests,
                 shutdownFailure, batchState, invalidCallback,
                 acknowledgementStartedPhase>>

Next ==
  \/ RequestStart
  \/ StartEngineSubmission
  \/ RejectEngineSubmission
  \/ StartEngineInvocation
  \/ RequestStop
  \/ ReceiveConnectorStarted
  \/ ReceiveConnectorStopped
  \/ ReceivePollingStarted
  \/ ReceivePollingStopped
  \/ ReceiveSuccessfulCompletion
  \/ ReceiveFailedCompletion
  \/ StartShutdownRequest
  \/ RecordShutdownFailure
  \/ AdmitBatch
  \/ HandleBatch
  \/ StartAcknowledgement
  \/ RecordAcknowledgement
  \/ RecordConsumerFailure
  \/ RecordAcknowledgementFailure
  \/ ConfirmGracefulCompletion

TypeOK ==
  /\ phase \in Phases
  /\ submissionStarted \in BOOLEAN
  /\ invocationStarted \in BOOLEAN
  /\ invocationCancelled \in BOOLEAN
  /\ connector \in ConnectorStates
  /\ polling \in PollingStates
  /\ stopRequested \in BOOLEAN
  /\ completion \in CompletionOutcomes
  /\ terminalAnomaly \in BOOLEAN
  /\ protocolAnomaly \in BOOLEAN
  /\ shutdownRequests \in 0..2
  /\ shutdownFailure \in BOOLEAN
  /\ batchState \in BatchStates
  /\ invalidCallback \in Callbacks \cup {NoCallback}
  /\ acknowledgementStartedPhase \in Phases \cup {NoPhase}
  /\ gracefulConfirmed \in BOOLEAN

CaptureHasNormalFacts ==
  phase = "capturing" =>
    /\ submissionStarted
    /\ invocationStarted
    /\ connector = "started"
    /\ polling = "started"
    /\ ~stopRequested
    /\ completion = "none"
    /\ ~terminalAnomaly

BarrierNeverRecaptures ==
  (stopRequested \/ polling = "stopped" \/ completion # "none" \/ terminalAnomaly)
    => phase # "capturing"

InvocationIsStartedOrCancelled ==
  invocationCancelled => ~invocationStarted

AcknowledgementStartsOnlyWhileCapturing ==
  acknowledgementStartedPhase # NoPhase => acknowledgementStartedPhase = "capturing"

InvalidCallbackRetainsRawFact ==
  protocolAnomaly => invalidCallback \in Callbacks

ShutdownRequestIsBounded == shutdownRequests \in 0..2

GracefulConfirmationHasEvidence ==
  gracefulConfirmed =>
    /\ completion = "succeeded"
    /\ ~terminalAnomaly
    /\ batchState \in {"none", "acknowledged"}
    /\ connector \in {"none", "stopped"}

Spec == Init /\ [][Next]_Vars

THEOREM Spec => []TypeOK
THEOREM Spec => []CaptureHasNormalFacts
THEOREM Spec => []BarrierNeverRecaptures
THEOREM Spec => []InvocationIsStartedOrCancelled
THEOREM Spec => []AcknowledgementStartsOnlyWhileCapturing
THEOREM Spec => []InvalidCallbackRetainsRawFact
THEOREM Spec => []ShutdownRequestIsBounded
THEOREM Spec => []GracefulConfirmationHasEvidence
====
