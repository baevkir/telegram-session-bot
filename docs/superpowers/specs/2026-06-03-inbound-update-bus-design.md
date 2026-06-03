# InboundUpdateBus Design Spec

> A pluggable inbound transport SPI for `CommandsSessionBot` plus a stable default that replaces
> the in-coordinator `groupBy(chatId)` lifecycle with `groupBy` + idle `timeout`, and an eager
> per-command stream completion in `TelegramUpdateHandler`.

## Goal

Extract inbound update intake out of `CommandsSessionBot` into a swappable `InboundUpdateBus` SPI,
and fix the resource-lifecycle weaknesses of the current `groupBy(UpdateWrapper::getChatId)` —
**without reinventing `groupBy`**. The fix leans on Reactor operators we already trust:

1. **Pluggability** — inbound transport becomes an interface (`@ConditionalOnMissingBean`), so a
   consuming app can replace it (webhook source, queue-backed source, custom partitioning) without
   touching the coordinator. Mirrors the existing `OutboundMessageBus`.
2. **Bounded, eagerly-released default** — `SinkInboundUpdateBus` groups updates per chat with
   `groupBy` and completes each chat's stream on **whichever comes first**: the command finishing
   (`takeUntil(close)` in the handler) or the chat going idle (`timeout(idleTtl, empty)` in the
   bus). Completed groups are dropped by `groupBy`, freeing memory and the coordinator's `flatMap`
   slots. Plus a configurable `maxConcurrentChats` to lift `flatMap`'s implicit 256 ceiling.

## Background — why the current `groupBy(chatId)` is changed

The coordinator currently does `updatesSink.asFlux().map(wrap).groupBy(chatId).flatMap(group ->
handleUpdates(group.publishOn(...)).onErrorResume(...))`. The logic (per-chat ordering, error
isolation) is correct, but the resource lifecycle has two footguns that are fine at small scale and
fail at large scale:

1. **Groups never complete → unbounded memory.** A `GroupedFlux` (with buffer) is kept per distinct
   key; it only completes when the source completes or the inner subscriber cancels — neither
   happens. Every distinct `chatId` ever seen accumulates a group that is never released.
2. **`flatMap` default concurrency = 256 → stall.** `flatMap` subscribes at most
   `Queues.SMALL_BUFFER_SIZE` (256) inner publishers concurrently. Since groups never complete, each
   holds a slot forever; after 256 active chats the 257th stalls (a latent deadlock that passes
   testing).

The fix makes groups **complete** (so `groupBy` evicts them and `flatMap` slots free) and makes the
fan-out concurrency configurable. We deliberately do **not** hand-roll a keyed cache to replace
`groupBy` — that would reimplement `groupBy`'s core (keyed map, per-key stream, recreate-on-next)
with new, untested concurrency code, for the sole gain of closing a rare teardown drop-race. That
trade isn't worth it for the default (see "Accepted limitations").

## Architecture

```
        Telegram long-poll thread
                 │ consume(update)
                 ▼
        ┌──────────────────────────────┐
        │       CommandsSessionBot      │  coordinator
        │  consume → inboundUpdateBus.emit(update)
        │  init:                        │
        │    inboundUpdateBus.updates() │  Flux<ChatUpdateStream>
        │      .flatMap(stream ->       │
        │         handler.handleUpdates(stream.updates().publishOn(boundedElastic))
        │           .onErrorResume(per-chat, logs stream.chatId()),
        │         maxConcurrentChats)   │
        └───────────────┬───────────────┘
                        │
                        ▼
              InboundUpdateBus (interface)
                emit(Update)
                Flux<ChatUpdateStream> updates()
                        │
                        ▼
            SinkInboundUpdateBus (default)
              Sinks.Many<Update> (single producer, FAIL_FAST)
              updates(): asFlux().map(wrap).groupBy(chatId)
                           .map(g -> ChatUpdateStream(g.key(), g.timeout(idleTtl, empty)))
```

The bus owns receiving (`emit`), wrapping (`UpdateWrapper::wrap`), per-chat grouping (`groupBy`) and
the idle safety-net (`timeout`). The coordinator owns fan-out + per-chat error isolation. The
handler owns eager completion on command `close`.

## Components

### `ChatUpdateStream` (record, new)

```java
package com.kb.sessionbot;

import com.kb.sessionbot.model.UpdateWrapper;
import reactor.core.publisher.Flux;

/** A single chat's ordered stream of updates, tagged with its chat id. */
public record ChatUpdateStream(String chatId, Flux<UpdateWrapper> updates) {}
```

Rationale: mirrors Reactor's `GroupedFlux.key()`. Key-upfront enables per-chat logging/metrics at
subscription time (the `onErrorResume` can log the real `chatId`) and key-based routing in custom
dispatchers, and self-documents the API. Composition (a record), not extending `Flux`.

### `InboundUpdateBus` (interface, new — the SPI)

```java
package com.kb.sessionbot;

import org.telegram.telegrambots.meta.api.objects.Update;
import reactor.core.publisher.Flux;

/**
 * Inbound transport + per-chat partitioning. {@code emit} is called once per received update (by
 * the coordinator's {@code consume}); {@code updates()} emits one {@link ChatUpdateStream} per
 * active chat, each carrying that chat's updates in arrival order.
 *
 * Contract: implementations MUST preserve per-chat arrival order within a stream, and SHOULD
 * complete a chat's stream when it goes idle so downstream fan-out slots are freed.
 */
public interface InboundUpdateBus {

    void emit(Update update);

    Flux<ChatUpdateStream> updates();
}
```

### `SinkInboundUpdateBus` (default impl, new)

```java
public class SinkInboundUpdateBus implements InboundUpdateBus {

    private final Sinks.Many<Update> sink = Sinks.many().unicast().onBackpressureBuffer();
    private final Duration idleTtl;

    public SinkInboundUpdateBus(Duration idleTtl) { this.idleTtl = idleTtl; }

    @Override
    public void emit(Update update) {
        sink.emitNext(update, Sinks.EmitFailureHandler.FAIL_FAST);   // single producer: poll thread
    }

    @Override
    public Flux<ChatUpdateStream> updates() {
        return sink.asFlux()
            .map(UpdateWrapper::wrap)
            .groupBy(UpdateWrapper::getChatId)
            .map(g -> new ChatUpdateStream(g.key(), g.timeout(idleTtl, Flux.empty())));
    }
}
```

- `emit` uses `FAIL_FAST`: `consume` is called by the single `LongPollingSingleThreadUpdateConsumer`
  thread, so there is no serialization contention (matches the current code).
- `g.timeout(idleTtl, Flux.empty())`: a per-item **sliding** idle timeout (resets on each update).
  When a chat is silent for `idleTtl`, the group switches to the empty fallback and **completes** →
  `groupBy` cancels and evicts the group → memory freed and the `flatMap` slot frees. The next
  update for that chat creates a fresh group.

### `TelegramUpdateHandler` (modified — eager completion on `close`)

Add a `takeUntil` so a chat's stream completes as soon as a command finishes, rather than lingering
until the idle timeout:

```java
return updates
    .scanWith(CommandContext::empty, (context, update) -> { ... })   // unchanged
    .skip(1)
    .takeUntil(context -> ContextState.close.equals(context.getState()))   // NEW: complete after close
    .concatMap(context -> { ... });                                  // unchanged
```

`takeUntil` emits the `close`-state context (so its completion results — including the cleanup
`DeleteMessage`s — are still processed by `concatMap`) and **then** completes. Completing
`handleUpdates`'s output cancels the upstream group, so `groupBy` evicts it immediately. The next
command for that chat creates a fresh group and a fresh `CommandContext`.

The `takeUntil(close)` (eager, for completed commands) and the bus's `timeout(idleTtl)` (safety net,
for abandoned in-progress commands that never reach `close`) compose: a stream completes on
whichever fires first.

### `CommandsSessionBot` (coordinator, modified)

- Remove the `Sinks.Many<Update> updatesSink` field, the `map(UpdateWrapper::wrap)`, and the
  `groupBy`.
- `consume(update)` → `inboundUpdateBus.emit(update)`.
- Inject `InboundUpdateBus inboundUpdateBus` and `int maxConcurrentChats`.
- Updates subscription:
  ```java
  subscriptions.add(
      inboundUpdateBus.updates()
          .flatMap(stream -> updateHandler.handleUpdates(stream.updates().publishOn(Schedulers.boundedElastic()))
              .onErrorResume(error -> {
                  log.warn("Handling pipeline error in chat {}", stream.chatId(), error);
                  return errorHandler.handle(error).doOnNext(messageExecutor::execute);
              }),
              maxConcurrentChats)
          .subscribe(
              ignored -> { },
              error -> log.error("Bot pipeline terminated unexpectedly", error)));
  ```
- The SetMyCommands and outbound subscriptions are unchanged.

## Configuration

New properties on `CommandsSessionBotProperties` (Spring Boot binds `Duration` and `int` natively):

| Property | Type | Default | Used by |
|---|---|---|---|
| `sessionbot.telegram.chat-idle-ttl` | `Duration` | `30m` | bus → `groupBy` per-group `timeout` |
| `sessionbot.telegram.max-concurrent-chats` | `int` | `256` | coordinator → `flatMap` concurrency |

- `chat-idle-ttl = 30m` — only the safety net for abandoned in-progress conversations now (completed
  ones are released eagerly by `takeUntil`), so a generous value is fine.
- `max-concurrent-chats = 256` — matches `flatMap`'s current implicit default; raise it if more than
  256 chats are expected active simultaneously.

## Wiring (auto-configuration)

```java
@Bean
@ConditionalOnMissingBean
public InboundUpdateBus inboundUpdateBus(CommandsSessionBotProperties properties) {
    return new SinkInboundUpdateBus(properties.getChatIdleTtl());
}

@Bean
public CommandsSessionBot bot(
        CommandsFactory commandsFactory,
        ErrorHandlerFactory errorHandler,
        MessageExecutor messageExecutor,
        OutboundMessageBus outboundMessageBus,
        TelegramUpdateHandler telegramUpdateHandler,
        InboundUpdateBus inboundUpdateBus,
        CommandsSessionBotProperties properties) {
    return new CommandsSessionBot(commandsFactory, errorHandler, messageExecutor,
        outboundMessageBus, telegramUpdateHandler, inboundUpdateBus,
        properties.getMaxConcurrentChats());
}
```

Only the `int` concurrency is passed to the coordinator (not the whole properties object), keeping
the coordinator's dependency surface minimal per the decomposition design.

## No new dependency

This design uses only Reactor operators already on the classpath (`groupBy`, `timeout`,
`takeUntil`, `flatMap`). No Caffeine or other third-party cache is added.

## Data flow

1. Poll thread → `consume(update)` → `bus.emit(update)` (single-producer `FAIL_FAST`).
2. `bus.updates()`: `wrap` → `groupBy(chatId)` → each group wrapped as a `ChatUpdateStream` whose
   flux carries `timeout(idleTtl, empty)`.
3. Coordinator `flatMap`s (bounded by `maxConcurrentChats`) each `ChatUpdateStream` into
   `handler.handleUpdates(stream.updates().publishOn(boundedElastic))`, executing results and
   isolating errors per chat.
4. Stream completion (whichever first):
   - command reaches `close` → `takeUntil` completes `handleUpdates` → group cancelled → `groupBy`
     evicts → slot freed (eager).
   - chat idle for `idleTtl` → `timeout` completes the group → same teardown (safety net).
5. Next update for an evicted chat → `groupBy` creates a fresh group → fresh `ChatUpdateStream` →
   fresh `handleUpdates`/`CommandContext`.

## Lifecycle & error handling

- **Per-chat error isolation:** `onErrorResume` wraps each inner stream and logs the real `chatId`;
  one chat's failure never affects the outer subscription. (An error terminates that chat's stream,
  which — like a normal close — lets `groupBy` evict and recreate on the next update.)
- **Two completion triggers** (`takeUntil(close)` and `timeout(idleTtl)`) as described above; both
  resolve to `groupBy` group teardown + recreate-on-next.

## Accepted limitations (documented behavior)

- **Teardown drop-race (accepted).** `groupBy` cannot atomically "complete a group and route the
  next same-key element to a new group." An update arriving in the brief teardown window after a
  group completes can be dropped (`onNextDropped`) rather than re-grouped. Eager completion makes
  this window occur right after each command, but human reaction time (hundreds of ms–seconds) far
  exceeds the teardown window (sub-millisecond), so collisions are vanishingly rare. A guaranteed
  drop-free dispatcher would require controlling routing ourselves (a keyed-cache reimplementation
  of `groupBy`), which is deliberately out of scope; an app that needs it can override
  `InboundUpdateBus` to return pre-grouped, drop-free streams.
- **Eviction mid-conversation discards `CommandContext`.** Only the *abandoned in-progress* case
  (idle `timeout` before `close`) is affected; the user re-issues the command. `chat-idle-ttl` is
  the safety margin. Pending `questionMessages` cleanup for an abandoned conversation is not
  auto-deleted.
- **Inbound stays single-consumer** (one Telegram poller per token). This SPI decouples the *source*
  (enabling a future `WebhookInboundUpdateBus`) but does not by itself enable multi-instance
  ingestion, which would also require externalizing `CommandContext`.

## Testing

- **`SinkInboundUpdateBusTest`** — uses `StepVerifier.withVirtualTime` for deterministic idle
  timing:
  - `emit` for one chat produces one `ChatUpdateStream`; same chat reuses it until completion.
  - Two distinct chats produce two streams; updates route to the correct stream in arrival order.
  - After `idleTtl` of no updates, the chat's inner flux completes (idle `timeout`).
  - A post-completion update for the same chat creates a fresh `ChatUpdateStream`.
- **`TelegramUpdateHandlerTest`** — add a case proving eager completion: a stream that reaches
  `close` completes after emitting the close result; an update sent *after* `close` on the same
  source is **not** processed (it would start a fresh group at the bus level, not extend the closed
  stream). Existing fold cases remain valid (finite sources complete as before).
- **`CommandsSessionBotTest`** (retargeted) — `consume`→`emit`→processed end-to-end; failure
  isolation across chats; `max-concurrent-chats` honored; `@PreDestroy` disposes.
- Reuse existing fixtures (`Fixtures`, `OrderCommand`, `EchoCommand`, `FixtureCommandConfig`).

### Implementation verification points

- Confirm the `close`-state cleanup (`DeleteMessage` of prior question/answer messages) is emitted
  *while processing the `close` context* (so `takeUntil` does not cut it off), against
  `DispatcherBotCommand`/the close transition. If cleanup is emitted on a later signal, adjust the
  `takeUntil` predicate accordingly.

## File structure

| File | Action |
|------|--------|
| `src/main/java/com/kb/sessionbot/ChatUpdateStream.java` | Create |
| `src/main/java/com/kb/sessionbot/InboundUpdateBus.java` | Create |
| `src/main/java/com/kb/sessionbot/SinkInboundUpdateBus.java` | Create |
| `src/main/java/com/kb/sessionbot/TelegramUpdateHandler.java` | Modify (add `takeUntil(close)`) |
| `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` | Modify (consume + init + fields) |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java` | Modify (2 properties) |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java` | Modify (bean + bot wiring) |
| `src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java` | Create |
| `src/test/java/com/kb/sessionbot/TelegramUpdateHandlerTest.java` | Modify (eager-completion case) |
| `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` | Modify (retarget to emit) |
