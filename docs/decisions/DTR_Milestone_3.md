# Decision Track Record — Milestone 3

## [M3] `CommandUtil` as shared process execution utility

**Context**: `GitService` contained two private methods (`runCommand`, `runCommandWithOutput`)
for executing shell processes via `ProcessBuilder`. The same logic was needed in test helpers
to initialise and manipulate temporary Git repositories without duplicating the implementation.

**Decision**: extract process execution into a standalone `CommandUtil` utility class in the
`util` package with static methods and an optional `LogService` parameter.

**Motivation**: eliminates duplication between production code and test helpers; keeps
`GitService` focused on Git semantics; `CommandUtil` is reusable for any future process
execution need.

**Design**: `runCommand(File dir, List<String> command)` overload without `LogService` for
test use where logging is not needed; `runCommand(File dir, List<String> command, LogService)`
for production use.

---

## [M3] Package-private test constructor on `SyncEvent`

**Context**: `SyncEventQueueTest` needed to create events with controlled timestamps to test
latest-wins deduplication deterministically. The production constructor sets `timestamp` via
`System.currentTimeMillis()` — two events created in the same millisecond are
indistinguishable.

**Decision**: add a package-private constructor `SyncEvent(EventType type, long timestamp)`
visible only within the `orchestrator` package.

**Motivation**: keeps `timestamp` semantically immutable from external callers while enabling
deterministic test scenarios without `Thread.sleep`. Preferred over a public `setTimestamp()`
setter which would expose unnecessary mutability in production.

**Trade-off accepted**: `timestamp` field removed `final` modifier to support the interim
`setTimestamp()` used before the test constructor was introduced. Tracked as a known code smell
in test code — the test constructor is the correct long-term solution.

---

## [M3] Package-private test constructor on `AutosaveScheduler`

**Context**: `AutosaveSchedulerTest` cannot wait real minutes for the scheduler to fire.
The production constructor accepts `intervalMinutes` as a `long` with implicit `TimeUnit.MINUTES`.

**Decision**: add a package-private constructor
`AutosaveScheduler(SyncEventQueue, LogService, long interval, TimeUnit timeUnit)`.
The public constructor delegates to it with `TimeUnit.MINUTES`.

**Motivation**: tests can use millisecond intervals without changing the public API used by
`Main`. The pattern is consistent with the approach used on `SyncEvent`.

---

## [M3] `SyncEventQueue` used as real instance in `SyncOrchestratorTest`

**Context**: initial test implementation attempted to mock `SyncEventQueue` via Mockito.
Mockito failed to mock it due to ByteBuddy limitations on Java 25.

**Decision**: use a real `SyncEventQueue` instance in `SyncOrchestratorTest`. Mock only
`GitService` and `NotificationHook`.

**Motivation**: `SyncEventQueue` is pure logic with no side effects — it is an ideal candidate
for a real instance in tests. Mocking it would test the mock, not the behaviour. The correct
rule is: mock external dependencies with side effects, use real instances for value objects
and pure logic.

---

## [M3] JDK downgrade from 25 to 21

**Context**: Mockito 5.12.0 uses ByteBuddy, which did not support Java 25 class files
(version 69). All mock creation failed with `IllegalArgumentException`.

**Decision**: downgrade the active JDK to Oracle OpenJDK 21 LTS. Update `pom.xml` to use
`maven.compiler.release=21`.

**Motivation**: Java 21 is the current LTS with broad adoption. A recruiter evaluating the
project is most likely running Java 21. Using `--release` instead of `source`/`target` also
sets the bootstrap classpath automatically, eliminating the related compiler warning.

---

## [M3] `readLogFile` returns empty string when file does not exist

**Context**: `LogServiceTest.log_belowMinLevel_doesNotWrite` verifies that a `DEBUG` message
is not written when `log.level=INFO`. Since nothing is written, the log file is never created.
`readLogFile` threw `RuntimeException` when the file was absent, causing the test to error
instead of pass.

**Decision**: `readLogFile` returns an empty string if the file does not exist, instead of
throwing an exception.

**Motivation**: absence of the file is the expected outcome of the test scenario — it is not
an error condition. Throwing here masked the correct assertion.

---

## [M3] `Thread.sleep` minimum values in scheduler tests

**Context**: `AutosaveSchedulerTest` used `Thread.sleep(2)` to wait for the scheduler to
fire after a 1ms interval. On Windows, the system timer has a resolution of approximately
15ms — `sleep(2)` can effectively sleep 0ms or 15ms depending on thread scheduling.

**Decision**: use `Thread.sleep(50)` as the minimum safe wait in scheduler tests on Windows.
This value exceeds the 15ms timer granularity with a safe margin without making tests
noticeably slow.

**Motivation**: flaky tests caused by timer resolution are hard to diagnose and reproduce.
A conservative sleep value eliminates the non-determinism entirely.