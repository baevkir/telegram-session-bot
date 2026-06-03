# InboundUpdateBus Design Spec

> A pluggable inbound transport SPI for `CommandsSessionBot` whose default groups updates per chat
> with `groupBy` + an idle `timeout`, replacing the in-coordinator `groupBy(chatId)` and fixing its
> unbounded-group / 256-slot footguns. No change to `TelegramUpdateHandler`.

## Goal

Extract inbound update intake out of `CommandsSessionBot` into a swappable `InboundUpdateBus` SPI,
and fix the resource-lifecycle weaknesses of the current `groupBy(UpdateWrapper::getChatId)` — using
only Reactor operators we already trust:

1. **Pluggability** — inbound transport becomes an interface (`@ConditionalOnMissingBean`), so a
   consuming app can replace it (webhook source, queue-backed source, custom partitioning) without
   touching the coordinator. Mirrors the existing `OutboundMessageBus`.
2. **Bounded default** — `SinkInboundUpdateBus` groups updates per chat with `groupBy` and completes
   each chat's stream after `chat-idle-ttl` of inactivity via `timeout(idleTtl, empty)`. Completed
   groups are dropped by `groupBy`, freeing memory and the coordinator's `flatMap` slots. A
   configurable `maxConcurrentChats` lifts `flatMap`'s implicit 256 ceiling.

`TelegramUpdateHandler` is **unchanged** — the idle timeout alone bounds resources, so no
per-command stream-completion logic is added (an earlier eager-completion idea was dropped: it fought
the mutable-`CommandContext` design and produced unreadable pipelines for only a release-latency
gain; tuning `chat-idle-ttl` down achieves the same effect via configuration).

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

The fix makes idle groups **complete** (so `groupBy` evicts them and `flatMap` slots free) via a
per-group `timeout`, and makes the fan-out concurrency configurable. We deliberately do **not**
hand-roll a keyed cache to replace `groupBy` — that would reimplement `groupBy`'s core with new,
untested concurrency code for the sole gain of closing a rare teardown drop-race; that trade is not
worth it for the default (see "Accepted limitations").

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
the idle release (`timeout`). The coordinator owns fan-out + per-chat error isolation. The handler is
untouched.

## Components

### `ChatUpdateStream` (record, new)

```java
package com.kb.sessionbot;

import com.kb.sessionbot.model.UpdateWrapper;
import reactor.core.publisher.Flux;

/** A single chat's ordered stream of updates, tagged with its chat id. */
public record ChatUpdateStream(String chatId, Flux<UpdateWrapper> updates) {}
```

Rationale: mirrors Reactor's `GroupedFlux.key()`. Key-upfront lets the coordinator's `onErrorResume`
log the real `chatId` and lets custom dispatchers route by key, and it self-documents the API.
Composition (a record), not extending `Flux`.

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
 * <p>Contract: implementations MUST preserve per-chat arrival order within a stream, and SHOULD
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
            .map(group -> new ChatUpdateStream(group.key(), group.timeout(idleTtl, Flux.empty())));
    }
}
```

- `emit` uses `FAIL_FAST`: `consume` is called by the single `LongPollingSingleThreadUpdateConsumer`
  thread, so there is no serialization contention (matches the current code).
- `group.timeout(idleTtl, Flux.empty())`: a per-item **sliding** idle timeout (resets on each
  update). When a chat is silent for `idleTtl`, the group switches to the empty fallback and
  **completes** → `groupBy` cancels and evicts the group → memory freed and the `flatMap` slot frees.
  The next update for that chat creates a fresh group.

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

- `chat-idle-ttl = 30m` — releases a chat's group after it is idle this long. Lower it (e.g. `2m`)
  for snappier release; raise it to keep long-paused conversations resident.
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
        outboundMessageBus, telegramUpdateHandler, inboundUpdateBus, properties.getMaxConcurrentChats());
}
```

Only the `int` concurrency is passed to the coordinator (not the whole properties object), keeping
the coordinator's dependency surface minimal per the decomposition design.

## No new dependency

This design uses only Reactor operators already on the classpath (`groupBy`, `timeout`, `flatMap`).
No Caffeine or other third-party cache is added.

## Data flow

1. Poll thread → `consume(update)` → `bus.emit(update)` (single-producer `FAIL_FAST`).
2. `bus.updates()`: `wrap` → `groupBy(chatId)` → each group wrapped as a `ChatUpdateStream` whose flux
   carries `timeout(idleTtl, empty)`.
3. Coordinator `flatMap`s (bounded by `maxConcurrentChats`) each `ChatUpdateStream` into
   `handler.handleUpdates(stream.updates().publishOn(boundedElastic))`, executing results and
   isolating errors per chat.
4. After `idleTtl` with no updates, a chat's group times out → completes → `groupBy` evicts it →
   `flatMap` slot frees. The next update for that chat creates a fresh group.

## Lifecycle & error handling

- **Per-chat error isolation:** `onErrorResume` wraps each inner stream and logs the real `chatId`;
  one chat's failure never affects the outer subscription. (An error terminates that chat's stream,
  which lets `groupBy` evict and recreate on the next update.)
- **Release:** the single trigger is the idle `timeout(idleTtl)` in the bus. No completion logic in
  the handler.

## Accepted limitations (documented behavior)

- **Teardown drop-race (accepted).** `groupBy` cannot atomically "complete a group and route the next
  same-key element to a new group." An update arriving in the brief teardown window after an idle
  group completes can be dropped (`onNextDropped`) rather than re-grouped. Because idle eviction fires
  only after `idleTtl` of silence — i.e. exactly when no update is imminent — the window almost never
  has a contender. A guaranteed drop-free dispatcher would require controlling routing ourselves (a
  keyed-cache reimplementation of `groupBy`), which is deliberately out of scope; an app that needs it
  can override `InboundUpdateBus`.
- **Eviction mid-conversation discards `CommandContext`.** A chat that starts a multi-step command and
  then goes idle past `idleTtl` loses its in-progress context; the user re-issues the command. Pending
  `questionMessages` cleanup for such a conversation is not auto-deleted. `chat-idle-ttl` is the safety
  margin (keep it comfortably longer than expected inter-step pauses).
- **Inbound stays single-consumer** (one Telegram poller per token). This SPI decouples the *source*
  (enabling a future `WebhookInboundUpdateBus`) but does not by itself enable multi-instance
  ingestion, which would also require externalizing `CommandContext`.

## Testing

- **`SinkInboundUpdateBusTest`**:
  - `emit` announces one `ChatUpdateStream` tagged with the chat id (StepVerifier on `updates()`).
  - Distinct chats produce distinct streams in arrival order.
  - A chat's stream completes after the idle TTL with no updates (StepVerifier `withVirtualTime`).
  - A new update after idle completion recreates a fresh stream for the same chat (virtual time).
- **`CommandsSessionBotTest`** (retargeted factory only): existing `consumeEndToEnd`, failure
  isolation, outbound, and shutdown cases continue to pass driving through the bus.
- **`TelegramUpdateHandlerTest`** — unchanged (the handler is not modified).
- Reuse existing fixtures (`Fixtures`, `OrderCommand`, `EchoCommand`, `FixtureCommandConfig`).

## File structure

| File | Action |
|------|--------|
| `src/main/java/com/kb/sessionbot/ChatUpdateStream.java` | Create |
| `src/main/java/com/kb/sessionbot/InboundUpdateBus.java` | Create |
| `src/main/java/com/kb/sessionbot/SinkInboundUpdateBus.java` | Create |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java` | Modify (2 properties) |
| `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` | Modify (consume + init + fields) |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java` | Modify (bean + wiring) |
| `src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java` | Create |
| `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` | Modify (retarget factory) |

`TelegramUpdateHandler` is intentionally **not** in this list.
