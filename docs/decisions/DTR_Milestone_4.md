# GRM — Milestone 4: Windows Integration

---

## DTR

---

### [M4] NetworkException vs GitException in GitService

**Context**: the retry with exponential backoff must apply only to network failures, not to
local Git errors (conflicts, empty stash, corrupted repository). Git returns generic exit codes
— the distinction must be inferred by parsing stderr.

**Decision**: `GitService` throws `NetworkException` for connectivity errors and `GitException`
for local errors. `SyncOrchestrator` handles the two cases with separate catch blocks. Retry
applies only to `NetworkException`.

**Implementation**: whitelist of known network error patterns. Any stderr output matching a
pattern in the list triggers `NetworkException`; everything else triggers `GitException`.

**Recognised network patterns**: `timeout`, `Could not resolve host`, `Connection refused`,
`Failed to connect`, `Network is unreachable`.

**Accepted risk**: on Windows installations with a localised Git, stderr messages may be in
Italian or another language. Verify on the target machine before finalising the whitelist.

**Motivation**: clear semantics; retry applied only where it makes sense. Standard pattern in
distributed systems.

---

### [M4] Persistent process with IPC over ephemeral per-task processes

**Context**: Task Scheduler launches a new JVM for each task — pull, push, autosave. If each
task instantiates its own `SyncOrchestrator`, the priority queue loses meaning: tasks never
coexist in the same queue and cannot be deduplicated or ordered.

**Decision**: one persistent process hosts the orchestrator. Tasks act as clients that send
an event via socket and terminate. The tray icon is the natural host process — it is already
persistent by design and already planned in the architecture.

**JAR modes**:
- `tray` → starts tray + socket server + orchestrator
- `logon` / `logoff` / `autosave` → socket client, sends event, exits

**Discarded alternative**: ephemeral process per task — simple, but the priority queue is
meaningless and deduplication is impossible.

---

### [M4] IPC via local TCP socket

**Context**: tasks need a mechanism to send events to the persistent tray process.
Three options evaluated: file lock, Windows named pipe, local TCP socket.

**Decision**: local TCP socket on `localhost:4242`. Port configurable via `config.properties`.

**Motivation**: bidirectional, portable across Windows and macOS, zero native API dependency.
The conceptual model of a TCP socket is identical to WebSocket — understanding one means
understanding the other. Named pipes have awkward Java APIs and are not portable. File locks
require polling and are not bidirectional.

---

### [M4] JSON message protocol on the socket

**Context**: a plain string over the socket is not extensible and carries no metadata.

**Decision**: JSON messages with fields `event`, `vaultId`, `timestamp`.

**Motivation**: extensible without breaking existing clients; `vaultId` is already predisposed
for multi-vault support; human-readable for debugging.

---

### [M4] Socket client retry: exponential backoff

**Context**: the tray process may not have completed startup when a logon task fires.
The client needs a retry policy to handle the startup window.

**Decision**: same policy as the orchestrator — exponential backoff 30s → 60s → 120s,
maximum 3 attempts.

**Motivation**: 30 seconds covers any realistic startup window on a modern system. Reusing
the same backoff constants as the orchestrator keeps the policy consistent across the system.

---

### [M4] PUSH_MANUAL and tray icon behaviour

**Context**: the user needs a visible, immediate way to push changes without waiting for
logoff.

**Decision**:
- **Left click** → publishes `PUSH_MANUAL` on the current vault directly to the internal
  orchestrator (same JVM — no IPC needed)
- **Right click** → popup with vault list + "Save on selection" boolean
    - Vault selection → updates `current-vault.json`
    - If "Save on selection" is active → automatically publishes `PUSH_MANUAL` on the
      newly selected vault

**Motivation**: "Save on selection" is a quality-of-life feature at minimal implementation
cost. Left click is the primary interaction — immediate, one gesture.

---

### [M4] Multi-vault: stateless GitService

**Context**: with multiple vaults, `GitService` must operate on different directories.
The current implementation reads `vault.path` once in the constructor and uses it for all
operations.

**Decision**: `GitService` becomes stateless — the vault path is passed as a parameter to
every method instead of being stored as a field.

**Motivation**: more testable (no constructor dependency on config), safer in concurrent
contexts (no shared mutable state), aligned with the stateless service model of microservices.

---

### [M4] vaults.json + current-vault.json separation

**Context**: vault configuration (path, remote, token) and runtime state (current vault,
last updated timestamp) have different lifecycles and different security requirements.

**Decision**: `vaults.json` stores static configuration. `current-vault.json` stores mutable
runtime state. Both excluded from `.gitignore`. `vaults.json.template` committed as reference.

**Motivation**: configuration and state change at different rates. Keeping them together would
expose credentials to frequent writes and risk of corruption. The separation is a habit worth
building before arriving at microservices, where config and state are always kept separate.

---

### [M4] AWT over Swing for the tray icon

**Context**: `SystemTray` and `TrayIcon` are AWT classes — they have no Swing equivalent.
The right-click popup can be implemented with AWT `PopupMenu` or Swing `JPopupMenu`.

**Decision**: AWT throughout. `PopupMenu` for the right-click menu.

**Motivation**: native OS look and feel, zero AWT→Swing bridge complexity, most stable
option on Windows 10 and 11.

**Compatibility target**: Windows 10 and Windows 11. Windows 8 as optional bonus.

---

### [M4] hasUncommittedChanges() as autosave guard instead of hasChanges()

**Context**: `hasChanges()` uses `git diff --quiet` — invisible to untracked files. A new
file appears in `git status` but is not committed by autosave. Counterintuitive for any
developer familiar with Git.

**Decision**: replace the autosave guard in `SyncOrchestrator` with `hasUncommittedChanges()`
which uses `git status --porcelain` — detects staged changes, unstaged changes, and untracked
files.

**Motivation**: alignment with the natural developer expectation — if `git status` sees it,
autosave commits it. `hasChanges()` is retained in `GitService` for potential future use.

---

### [M4] .obsidian/ partially tracked in vaults

**Context**: `.obsidian/workspace.json` stores the UI state — open panels, active file,
cursor position. Syncing it via `PUSH_LOGOFF` / `PULL_LOGON` means opening Obsidian on a
second machine resumes exactly where the session left off on the first.

**Decision**: track `.obsidian/` contents except plugin binaries. Exclude `plugins/*/main.js`
and `plugins/*/styles.css` via `.gitignore`.

**Motivation**: session continuity is a genuine feature delivered for free by the existing
sync mechanism. Plugin binaries are large and reinstallable from Obsidian — no value in
tracking them.

**Trade-off accepted**: conflicts on `workspace.json` are resolved by the `theirs` strategy —
the last session always wins.

---

## [M4] push() always executed on PUSH_LOGOFF regardless of commit result

**Context**: autosave commits locally on every cycle without pushing to remote.
At PUSH_LOGOFF there may be N locally accumulated commits to push even when the working
tree is clean. The original code skipped the push if commitLocal() returned a non-zero
exit code (nothing to commit).

**Decision**: push() is always called in the PUSH_LOGOFF and PUSH_MANUAL cases,
regardless of commitLocal() exit code.

**Motivation**: "nothing to commit" and "nothing to push" are distinct conditions.
Autosave accumulates local commits during the session — logoff is the moment they are
transmitted to remote. Skipping the push in the absence of new local changes was
nullifying the entire autosave → logoff push pipeline.

---

## Sprint objectives — definition of done

| # | Objective | Acceptance criteria |
|---|---|---|
| 1 | `NetworkException` / `GitException` in `GitService` | network pattern whitelist, separate catches in `SyncOrchestrator` |
| 2 | Client/server mode in the JAR | `tray` starts server, `logon`/`logoff`/`autosave` send event via socket |
| 3 | Socket server on `localhost:4242` | receives JSON, publishes event on `SyncOrchestrator` |
| 4 | Socket client with retry | exponential backoff 30s→60s→120s, max 3 attempts |
| 5 | Stateless multi-vault `GitService` | vault passed as parameter to all methods |
| 6 | `vaults.json` + `vaults.json.template` | structure defined, excluded from `.gitignore` |
| 7 | `current-vault.json` | written on first run, updated on vault change |
| 8 | Tray icon — left click | publishes `PUSH_MANUAL` on current vault |
| 9 | Tray icon — right click | popup with vault list + "save on selection" boolean |
| 10 | Task Scheduler — 3 tasks configured | logon, logoff, autosave launch the JAR with the correct argument |
| 11 | E2E checklist passed | all 8 scenarios validated on test vault |

---

## Identified risks

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| Git stderr messages localised in Italian on Windows | Medium | High | test on real machine before finalising whitelist |
| Port 4242 occupied on some machines | Low | Medium | port configurable via `config.properties` |
| AWT tray icon on Windows 11 | Medium | High | test `SystemTray.isSupported()` at bootstrap; Windows 10 is primary target |
| Concurrent writes on `current-vault.json` | Medium | Medium | synchronise writes with `synchronized` |
| Task Scheduler logoff does not wait for JAR completion | Medium | High | configure task with "wait for task to complete" |
| Missing remote URL not restored after crash in e2e test | Low | Medium | explicit teardown script in the checklist |

---

## Know-how to acquire before writing

| Area | Concept | Why it is needed |
|---|---|---|
| Java Networking | `ServerSocket`, `Socket`, `BufferedReader`, `PrintWriter` | socket server and client |
| Java AWT | `SystemTray`, `TrayIcon`, `PopupMenu`, `MenuItem` | tray icon with left/right click |
| Java AWT | Event Dispatch Thread, `SwingUtilities.invokeLater()` | thread safety between tray and orchestrator |
| JSON in Java | `org.json` or `Jackson` — parsing and serialisation | socket protocol and `vaults.json` reading |
| Windows Task Scheduler | logon/logoff triggers, execution options, user account | configuration of the 3 tasks |
| Git internals | stderr parsing, network error patterns | `NetworkException` whitelist implementation |

---

# Extra DTR

---

## [M4] PULL_MANUAL — manual pull from tray icon

**Context**: a user with two machines always on never gets an automatic PULL_LOGON.
Changes pushed from one device do not reach the other until an explicit pull is performed.
This affects any persistent-session workflow — machines that are never shut down, always-on
servers, or users working across multiple devices without logoff.

**Decision**: add `EventType.PULL_MANUAL` with priority 1, equal to `PULL_LOGON`.
Accessible via a refresh icon next to each vault entry in the tray icon right-click popup.

**Motivation**: `PULL_LOGON` solves the standard case. `PULL_MANUAL` solves the persistent
session case — where the automatic trigger never fires and the user needs a way to
explicitly align the local vault with remote without a logoff/logon cycle.

**Impact**: minimal on the backend — `PULL_MANUAL` shares the same case branch as
`PULL_LOGON` in `SyncOrchestrator`. On the tray side: integrated naturally into the
multi-vault popup already planned, at no additional architectural cost.

---

## [M4] Caller logging deferred to a mature logging framework

**Context**: during `mvn test` debugging, the need emerged to programmatically identify
the caller on each log line — to distinguish which test or component produced a given
message without manual stack analysis.

**Decision**: do not implement caller logging in `LogService`. Deferred.

**Motivation**: `Thread.currentThread().getStackTrace()` is native Java and technically
feasible, but adds noise in production — every line carries the caller name even when
irrelevant. The standard solution is a mature logging framework (slf4j + logback) with
MDC (Mapped Diagnostic Context), which handles this feature in an environment-configurable
way. Adding a custom solution now would mean rewriting it when logback is adopted in
the microservices layer.

**Principle**: KISS — Better Done Than Perfect. The value does not justify the effort
at this stage of the project.

**Next step**: when NomadSync is integrated into the ToDoList 2.0 microservices
architecture, `LogService` will be replaced by slf4j + logback. Caller logging will
be available at no cost via MDC without a single additional line of code.

---

## [M4] Multi-vault architecture with VaultContext and global priority queue

**Context**: NomadSync was designed as a single-vault tool. The introduction of the
socket layer (SocketServer/SocketClient) surfaced the need to handle multiple vaults
with isolated queues and cross-vault event ordering.

**Decision**: each registered vault gets a dedicated `SyncEventQueue` and aggregator
thread, encapsulated in `VaultContext`. A global `PriorityBlockingQueue<SyncEvent>`
collects events from all per-vault queues and delivers them to the worker in
cross-vault priority order.

**Motivation**: vault isolation — a slow vault does not block others. Global priority
is guaranteed by construction — a `PULL_LOGON` on vault A always precedes an
`AUTOSAVE` on vault B regardless of arrival order.

**Discarded alternative**: `vaultId` as a routing field in a single shared queue
without isolation — simpler but without vault isolation guarantees.

---

## [M4] Composition over inheritance for VaultContext

**Context**: `VaultContext` needs access to `Vault` fields (id, name, path) and adds
runtime state (queue, aggregator thread, future). Inheritance would have mixed an
immutable value object with mutable execution state.

**Decision**: `VaultContext` holds `Vault` as a `final` field — composition.

**Motivation**: `Vault` is a value object deserialised from JSON with a lifecycle
distinct from the runtime. Keeping them separate is a habit worth building before
arriving at microservices, where configuration and state always live in separate classes.

---

## [M4] DTO layer for JSON parsing — SocketMessageDto

**Context**: Jackson deserialisation requires a no-arg constructor or `@JsonCreator`.
Applying Jackson annotations to `SyncEvent` would have introduced a transport layer
dependency into the domain model — an architectural red flag.

**Decision**: introduce `package dto` with `SocketMessageDto` annotated with
`@JsonCreator` and `@JsonProperty`. `JsonMapper` converts the DTO to `SyncEvent`
without exposing Jackson to the domain.

**Motivation**: clean separation between transport contract and domain model.
The pattern is identical to the Spring Boot request/response DTO pattern.

---

## [M4] JsonMapper as serialisation utility layer

**Context**: Jackson serialisation/deserialisation logic was scattered across
`SocketServer`, `SocketClient`, and domain classes.

**Decision**: centralise in `JsonMapper` with static methods — a single shared
`ObjectMapper` instance, thread-safe after initial configuration.

**Motivation**: `ObjectMapper` is expensive to instantiate — creating one per call
is a documented anti-pattern. Centralisation eliminates duplication and provides
a single Jackson configuration point.

---

## [M4] SocketServer — receiver and router separation

**Context**: the server must accept incoming connections (blocking on `accept()`)
and route events to per-vault queues (blocking on `take()`). Running both operations
on the same thread would serialise reception and routing.

**Decision**: two dedicated threads — `receiver` (loop on `accept()`) and `router`
(loop on `mainQueue.take()`). They communicate via `mainQueue`.

**Motivation**: decoupling between network I/O and routing logic. The receiver does
not block on routing; the router does not block on accepting new connections.
`mainQueue.take()` instead of `poll()` + sleep eliminates busy-waiting.

---

## [M4] AUTOSAVE as broadcast event with null vaultId

**Context**: `AutosaveScheduler` publishes periodic events without knowing the list
of registered vaults. Passing vaultId to the scheduler constructor required an update
on every vault add or remove at runtime.

**Decision**: `AUTOSAVE` is published with `vaultId = null` as a broadcast sentinel.
`SocketServer.doWork()` expands the event into one per vault via `SyncEvent.forVault(String)`
before publishing to per-vault queues.

**Motivation**: `AutosaveScheduler` remains vault-agnostic — zero coupling to the
vault list, zero runtime updates. Broadcast is a routing responsibility, not a
publisher responsibility.

**Trade-off accepted**: `doWork` must distinguish broadcast events from targeted ones
via a null check on `vaultId`. Documented in javadoc of `SyncEvent` and `SocketServer`.

---

## [M4] SyncEvent.forVault() — targeted copy of a broadcast event

**Context**: broadcast requires creating N targeted events from one broadcast event,
preserving the original timestamp and retryDelay to guarantee consistent cross-vault
ordering.

**Decision**: `forVault(String vaultId)` method on `SyncEvent` returns a copy with
the vaultId assigned. Throws `UnsupportedOperationException` if the event already has
a vaultId — defensive guard against misuse.

**Motivation**: the copy preserves the original timestamp — all events generated from
one broadcast share the same temporal priority, guaranteeing cross-vault fairness in
queue ordering.

---

## [M4] SocketServer — readLine() instead of extractJson() for request-response protocol

**Context**: the receiver used `JsonMapper.extractJson()` backed by Jackson's
streaming parser. Jackson holds the stream open until EOF — in a request-response
socket protocol the client does not close the connection before reading the response,
producing a deadlock.

**Decision**: replace `extractJson()` with `BufferedReader.readLine()` in the receiver.
The client sends a single JSON line terminated by `\n` via `PrintWriter.println()` —
`readLine()` stops at the newline without waiting for socket close.

**Motivation**: the protocol is already line-based — client and server exchange one
line per direction. `readLine()` is the correct primitive for this pattern.
`extractJson()` remains available for non-line-based stream contexts.

---

## [M4] SocketServer — ACK timing: after enqueue, before socket close

**Context**: the first implementation sent ACK after offering the event to mainQueue.
The client read null because the server closed the socket in the finally block before
the ACK was written.

**Decision**: the server writes ACK/NACK/ERROR before the finally block closes the
socket. `PrintWriter` with `autoFlush=true` guarantees the response is flushed to
the wire before `socket.close()`.

**Motivation**: the protocol guarantees exactly one response per connection before
close. Ordering write-before-close eliminates the race between response delivery
and socket teardown.

---

## [M4] SocketServer — three response levels: ACK / NACK / ERROR

**Context**: the server must distinguish between an unknown event type (semantic error)
and malformed JSON (syntactic error). Treating them identically prevents the client
from understanding the nature of the failure.

**Decision**:
- `ACK` — event parsed and enqueued successfully
- `NACK` — `IllegalArgumentException` on `EventType.valueOf()` — unknown event type
- `ERROR` — `JsonProcessingException` — malformed JSON

**Motivation**: protocol-level separation of concerns. The client can react
differently — retry on ERROR, no retry on NACK.

---

## [M4] SyncOrchestrator — one per vault, started via ScheduledFuture

**Context**: the single-orchestrator design did not scale to multi-vault. A proposed
per-vault aggregator bridging to a global queue created circular logic
(queue → aggregator → queue).

**Decision**: one `SyncOrchestrator` per vault, started with
`scheduler.schedule(orchestrator::start, 0, MILLISECONDS)`. The returned
`ScheduledFuture` is stored in `VaultContext` for `cancel(true)` at shutdown.

**Motivation**: each vault gets an isolated queue and a serial worker. Cross-vault
priority is guaranteed by the global `PriorityBlockingQueue` in `SocketServer`.
The `ScheduledFuture` pattern centralises thread lifecycle management without
introducing intermediate aggregators.

**Discarded alternative**: per-vault aggregator consuming from vault queue and
re-publishing to mainQueue — circular logic, added complexity, no benefit.