# InboundUpdateBus Design Spec

> A pluggable inbound transport SPI for `CommandsSessionBot` whose default groups updates per chat
> with `groupBy` + an idle `timeout`, replacing the in-coordinator `groupBy(chatId)` and fixing its
> unbounded-group / 256-slot footguns. `TelegramUpdateHandler` additionally completes a chat's
> stream as soon as its command closes, so completed conversations release immediately — the key
> memory-leak fix.

## Goal

Extract inbound update intake out of `CommandsSessionBot` into a swappable `InboundUpdateBus` SPI,
and fix the resource-lifecycle weaknesses of the current `groupBy(UpdateWrapper::getChatId)` — using
only Reactor operators we already trust:

1. **Pluggability** — inbound transport becomes an interface (`@ConditionalOnMissingBean`), so a
   consuming app can replace it (webhook source, queue-backed source, custom partitioning) without
   touching the coordinator. Mirrors the existing `OutboundMessageBus`.
2. **Bounded default** — `SinkInboundUpdateBus` groups updates per chat with `groupBy`; a chat's
   stream completes on **whichever comes first**:
   - the command **closing** — `TelegramUpdateHandler` collects each context's results and completes
     the stream once a command reaches `close` (eager release; the main memory-leak fix);
   - the chat going **idle** — `group.timeout(idleTtl, empty)` releases abandoned in-progress
     conversations that never reach `close`.
   Either way the group completes, so `groupBy` evicts it and the coordinator's `flatMap` slot frees.
   A configurable `maxConcurrentChats` lifts `flatMap`'s implicit 256 ceiling.

## Background — why the current `groupBy(chatId)` leaks

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

The fix makes a chat's inner stream **complete** (so `groupBy` evicts the group and the `flatMap`
slot frees) — eagerly when a command closes, and as a safety net when a chat goes idle — and makes
the fan-out concurrency configurable. We deliberately do **not** hand-roll a keyed cache to replace
`groupBy` (that would reimplement `groupBy`'s core with new, untested concurrency code).

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

The bus owns receiving (`emit`), wrapping, per-chat grouping (`groupBy`) and the idle safety-net
(`timeout`). The coordinator owns fan-out + per-chat error isolation. The handler dispatches each
context and **completes the per-chat stream once a command closes**.

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
log the real `chatId` and lets custom dispatchers route by key, and self-documents the API.

### `InboundUpdateBus` (interface, new — the SPI)

```java
package com.kb.sessionbot;

import org.telegram.telegrambots.meta.api.objects.Update;
import reactor.core.publisher.Flux;

/**
 * Inbound transport + per-chat partitioning. {@code emit} is called once per received update;
 * {@code updates()} emits one {@link ChatUpdateStream} per active chat, in arrival order.
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
  thread, so there is no serialization contention.
- `group.timeout(idleTtl, Flux.empty())`: a per-item sliding idle timeout — the **safety net** for a
  chat that starts a multi-step command then never finishes. (Completed commands are released earlier
  by the handler; see below.)

### `TelegramUpdateHandler` (modified — complete on close)

The handler folds updates into a `CommandContext`, dispatches each, and **completes the stream once a
command closes**. Because `CommandContext` is mutable and `close()` is set *inside* dispatch, the
completion decision is made at context granularity, *after* dispatch — each context's results are
collected, then a `takeUntil` on the post-dispatch state completes the stream after the close
context's full output (cleanup `DeleteMessage`s included):

```java
public Flux<PartialBotApiMethod<?>> handleUpdates(Flux<UpdateWrapper> updates) {
    Assert.notNull(updates, "Updates is null.");
    return updates
        .scanWith(CommandContext::empty, this::fold)
        .skip(1) // drop the empty seed context emitted before any update
        .concatMap(context -> dispatch(context).collectList()
            .map(results -> new DispatchOutcome(context, results)))
        .takeUntil(outcome -> ContextState.close.equals(outcome.context().getState()))
        .concatMapIterable(DispatchOutcome::results);
}
```

`fold` (the scan accumulator) and `dispatch` (help / command / auth-error, with the inline
`messageExecutor::execute` + `addQuestionMessage` side effects) are extracted private methods; their
bodies are unchanged from the current handler. `DispatchOutcome` is a private record
`(CommandContext context, List<PartialBotApiMethod<?>> results)`.

Correctness notes:
- `collectList` runs dispatch to completion first, so `outcome.context().getState()` is the *final*
  state; `takeUntil` decides once per context, never mid-results — cleanup `DeleteMessage`s are never
  cut off.
- Inline execution and `addQuestionMessage` are unchanged: `dispatch`'s `doOnNext` still executes and
  records as results are produced (during collection), in order.
- Auth-rejected dispatch errors with `BotAuthException`; `collectList` propagates it, so the
  coordinator's `onErrorResume` handles it exactly as today.
- After a close, the stream completes → the group is cancelled → `groupBy` evicts it → the next
  command for that chat starts a fresh group.

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
          .subscribe(ignored -> { }, error -> log.error("Bot pipeline terminated unexpectedly", error)));
  ```
- SetMyCommands and outbound subscriptions unchanged.

## Configuration

| Property | Type | Default | Used by |
|---|---|---|---|
| `sessionbot.telegram.chat-idle-ttl` | `Duration` | `30m` | bus → `groupBy` per-group `timeout` |
| `sessionbot.telegram.max-concurrent-chats` | `int` | `256` | coordinator → `flatMap` concurrency |

- `chat-idle-ttl = 30m` — now only the safety net for *abandoned in-progress* conversations
  (completed commands are released eagerly), so a generous value is fine; keep it comfortably longer
  than expected inter-step pauses.
- `max-concurrent-chats = 256` — matches `flatMap`'s implicit default; raise for higher concurrency.

## Wiring (auto-configuration)

```java
@Bean
@ConditionalOnMissingBean
public InboundUpdateBus inboundUpdateBus(CommandsSessionBotProperties properties) {
    return new SinkInboundUpdateBus(properties.getChatIdleTtl());
}

@Bean
public CommandsSessionBot bot(
        CommandsFactory commandsFactory, ErrorHandlerFactory errorHandler, MessageExecutor messageExecutor,
        OutboundMessageBus outboundMessageBus, TelegramUpdateHandler telegramUpdateHandler,
        InboundUpdateBus inboundUpdateBus, CommandsSessionBotProperties properties) {
    return new CommandsSessionBot(commandsFactory, errorHandler, messageExecutor,
        outboundMessageBus, telegramUpdateHandler, inboundUpdateBus, properties.getMaxConcurrentChats());
}
```

## No new dependency

Only Reactor operators already on the classpath (`groupBy`, `timeout`, `flatMap`, `concatMap`,
`collectList`, `takeUntil`, `concatMapIterable`). No Caffeine or other third-party cache.

## Lifecycle & error handling

- **Two release triggers:** the handler's `takeUntil(close)` (eager, for completed commands) and the
  bus's `timeout(idleTtl)` (safety net, for abandoned in-progress commands). A stream completes on
  whichever fires first; either way `groupBy` evicts the group.
- **Per-chat error isolation:** `onErrorResume` wraps each inner stream and logs the real `chatId`;
  one chat's failure never affects the outer subscription. An error also terminates that chat's
  stream, letting `groupBy` evict and recreate on the next update.

## Accepted limitations (documented)

- **Teardown drop-race (accepted).** `groupBy` cannot atomically "complete a group and route the next
  same-key element to a new group." An update arriving in the brief teardown window after a group
  completes can be dropped (`onNextDropped`). With eager completion this window occurs right after
  each command, but human reaction time (hundreds of ms–seconds) far exceeds the teardown window
  (sub-millisecond), so collisions are vanishingly rare. A guaranteed drop-free dispatcher would
  require controlling routing ourselves (a keyed-cache reimplementation of `groupBy`), out of scope;
  an app that needs it can override `InboundUpdateBus`.
- **Idle eviction discards in-progress `CommandContext`.** A chat that abandons a multi-step command
  mid-flight loses its context after `chat-idle-ttl`; the user re-issues. Pending `questionMessages`
  cleanup for such a conversation is not auto-deleted.
- **Inbound stays single-consumer** (one Telegram poller per token). This SPI decouples the *source*
  (enabling a future `WebhookInboundUpdateBus`) but does not by itself enable multi-instance
  ingestion (which would also require externalizing `CommandContext`).

## Testing

- **`TelegramUpdateHandlerTest`** — add `completesAfterCommandClose`: two back-to-back completing
  commands; only the first is processed (the stream completes after its close). All existing fold
  cases stay green.
- **`SinkInboundUpdateBusTest`** — `emit` announces one `ChatUpdateStream`; distinct chats →
  distinct streams in order; idle TTL completes a stream (`StepVerifier.withVirtualTime`); a new
  update after idle completion recreates a fresh stream.
- **`CommandsSessionBotTest`** — retarget the factory only; existing `consumeEndToEnd`, failure
  isolation, outbound, and shutdown cases continue to pass driving through the bus.
- Reuse existing fixtures (`Fixtures`, `OrderCommand`, `EchoCommand`, `FixtureCommandConfig`).

## File structure

| File | Action |
|------|--------|
| `src/main/java/com/kb/sessionbot/TelegramUpdateHandler.java` | Modify (complete on close, via collectList) |
| `src/test/java/com/kb/sessionbot/TelegramUpdateHandlerTest.java` | Modify (eager-completion test) |
| `src/main/java/com/kb/sessionbot/ChatUpdateStream.java` | Create |
| `src/main/java/com/kb/sessionbot/InboundUpdateBus.java` | Create |
| `src/main/java/com/kb/sessionbot/SinkInboundUpdateBus.java` | Create |
| `src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java` | Create |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java` | Modify (2 properties) |
| `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` | Modify (consume + init + fields) |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java` | Modify (bean + wiring) |
| `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` | Modify (retarget factory) |
