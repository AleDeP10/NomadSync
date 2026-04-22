# Decision Track Record — Milestone 2

## [M2] Event-driven architecture for SyncOrchestrator

**Context**: `SyncOrchestrator` must coordinate Git operations coming from multiple callers
(Task Scheduler logon/logoff, `AutosaveScheduler`, tray icon). Git is serial — concurrent
operations on the same repository cause conflicts.

**Decision**: event-driven architecture with a priority queue. Callers publish events;
the orchestrator consumes from the queue serially.

**Motivation**: concurrency is managed in a single place; callers are decoupled from the
orchestrator; the pattern prepares the mindset for microservices.

**Discarded alternatives**:
- Direct calls (`orchestrator.pull()`) — every caller must know the orchestrator;
  concurrency handling spreads across the codebase.

---

## [M2] Event priority scale

**Context**: events of different types may coexist in the queue simultaneously. An ordering
that reflects the relative importance of operations is required.

**Decision**:

| Priority | Event |
|---|---|
| 1 | PULL_LOGON |
| 2 | PUSH_MANUAL |
| 3 | PUSH_LOGOFF |
| 4 | AUTOSAVE |

**Motivation**: pull is a precondition for everything else. Manual push reflects explicit
user intent and precedes logoff. Autosave is tolerant and deferrable.

---

## [M2] Deduplication strategy: latest wins

**Context**: events of the same type can accumulate in the queue (e.g. autosave every
15 minutes, double-click on the tray icon).

**Decision**: a queued event is replaced by the most recent one of the same type.
An event currently being executed is not interrupted — the new event waits in the queue.

**Motivation**: an autosave represents a snapshot of the current moment, not an incremental
operation. Queuing two of them adds no value.

---

## [M2] Retry with exponential backoff

**Context**: Git operations can fail due to network absence. Unlimited retries saturate the
queue; fixed-interval retries hammer an unavailable resource.

**Decision**: exponential backoff with a maximum of 3 attempts.
Progressive delay: 30s → 60s → 120s. After the third failure the event is discarded.

**Motivation**: standard pattern in distributed systems. Avoids overloading an unavailable
resource. Maximum wait time (~3.5 minutes) acceptable for a logon pull.

**Accepted risks**: if the network is absent for the entire session, the logon pull fails
definitively. Mitigated by the notification hook.

---

## [M2] Notification hook as dependency inversion

**Context**: priority-1 failures (PULL_LOGON) must be communicated to the user.
The tray icon is out of scope for this sprint.

**Decision**: the orchestrator exposes a `NotificationHook` interface with a default
implementation that writes to the log. The tray attaches later by implementing the same
interface without modifying the orchestrator.

**Motivation**: dependency inversion — the orchestrator depends on the abstraction, not
the implementation. Prepares the architecture for the tray without blocking the current sprint.

**Status**: interface scaffolded, tray implementation in backlog.

---

## [M2] Separation of local commit and remote push

**Context**: autosave is a frequent, silent checkpoint. Manual push and logoff are
remote synchronisation operations. Treating them the same way would expose autosave
to unnecessary network failures.

**Decision**:
- `AUTOSAVE` → local commit only
- `PUSH_MANUAL` / `PUSH_LOGOFF` → local commit + remote push

**Motivation**: resilience — autosave works without a network connection. Separation of
concerns — local commit is always available; remote push is a distinct and fallible operation.
Exponential backoff retry applies only to operations that touch the remote.

---

## [M2] `hasUncommittedChanges()` guard before stash/stashPop

**Context**: `git stash pop` on an empty stash returns exit code 1 with an error. If the
logon pull runs on a clean working tree, the stash is empty and the subsequent `stashPop`
would fail, unnecessarily triggering the retry logic.

**Decision**: `gitService.hasUncommittedChanges()` as a mandatory guard before `stash()`
and `stashPop()`. `stashPop()` is called only if `stash()` was called.

**Implementation**: `git status --porcelain` — stable, locale-independent output.
Empty output = clean working tree. Non-empty output = changes present (staged or unstaged).

**Motivation**: `git diff --quiet` checks only unstaged changes — insufficient.
`git status --porcelain` covers all cases including staged but uncommitted changes.

---

## [M2] `notify()` renamed to `onFailure()` in NotificationHook

**Context**: the `NotificationHook` interface defined a method `notify(SyncEvent event)`.
`Object.notify()` is a native Java method on all objects — the name collision generates
ambiguity and compiler warnings.

**Decision**: method renamed to `onFailure(SyncEvent event, String message)` with the
addition of a `message` parameter to communicate the actual failure cause.

**Motivation**: semantic clarity — `onFailure` describes the contract exactly.
The `message` parameter allows a future tray implementation to display a contextual
message to the user without inspecting the event.

---

## [M2] Plain Thread instead of ExecutorService for the worker loop

**Context**: `SyncOrchestrator` needs a dedicated thread running an infinite loop consuming
events from the queue. An earlier draft passed an `ExecutorService` via the constructor.

**Decision**: plain `Thread` instantiated internally by the orchestrator.

**Motivation**: `ExecutorService` is useful for thread pools or tasks with `Future` semantics.
A single worker with an infinite loop benefits from none of its abstractions — it added a
constructor parameter without providing value. Simplicity is preferred.

**Shutdown**: `worker.interrupt()` unblocks `queue.consume()` (which internally calls
`PriorityBlockingQueue.take()`); the loop catches `InterruptedException` and terminates.
`worker.join()` in `stop()` ensures the current task finishes before the JVM shuts down.

**Discarded alternative**: `ExecutorService` with `shutdown()` — correct but oversized
for a single thread with loop semantics.

---

## [M2] Shutdown hook registered in Main, not in SyncOrchestrator

**Context**: the first implementation registered the JVM shutdown hook inside
`SyncOrchestrator.start()`. This prevented controlling the shutdown order between the
scheduler and the orchestrator.

**Decision**: shutdown hook registered in `Main`, which owns the wiring of all components.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    scheduler.stop();     // stop publishing first
    orchestrator.stop();  // then drain and stop consuming
}, "obsidiansync-shutdown"));
```

**Motivation**: `Main` is the natural place to decide shutdown order — the same place that
decides startup order. Stopping the scheduler first prevents publishing events onto a queue
that is no longer being consumed.

---

## [M2] Adoption of Git Flow as branching strategy

**Context**: the project grows in complexity and milestones follow each other with distinct
objectives. Committing directly to `main` or `develop` does not separate work in progress
from integrated code.

**Decision**: Git Flow AVH Edition as the standard branching strategy.

| Branch | Role |
|---|---|
| `main` | Released code only. Tagged at every milestone. |
| `develop` | Continuous sprint integration. |
| `feature/*` | One branch per grooming objective. |
| `release/*` | Release preparation — version bump, final fixes. |
| `hotfix/*` | Urgent fixes on `main`, merged into `develop` as well. |

**Motivation**: clean separation between work in progress and stable code; per-feature diffs
readable by recruiters; direct mapping to the adopted Agile methodology — each grooming
objective becomes a `feature/*` branch.

**Operational note**: Git Flow AVH Edition is bundled with Git for Windows by default —
no separate installation required. Initialised with `git flow init` on an existing repository,
without affecting existing branches or history.

**Discarded alternatives**: trunk-based development — suited to teams with mature CI/CD;
premature for a solo learning project.
