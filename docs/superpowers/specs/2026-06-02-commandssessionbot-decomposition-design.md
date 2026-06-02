# Design: CommandsSessionBot decomposition

**Date:** 2026-06-02
**Branch:** `commandssessionbot-decomposition` (off master)
**Status:** Approved design, ready for implementation plan

## Goal

Break the overgrown `CommandsSessionBot` (one class doing update intake, outbound publishing,
pipeline wiring, per-chat fold/dispatch, message execution, and lifecycle) into focused,
independently-testable units — **without changing observable behavior** and **preserving every
concurrency guarantee** introduced in the runtime-correctness spec.

## Scope

**In scope:** extract `MessageExecutor`, `TelegramUpdateHandler`, and `OutboundMessages` from
`CommandsSessionBot`; reduce `CommandsSessionBot` to a thin coordinator; replace the single
merged subscription with independent per-stream subscriptions; rewire the auto-config beans;
move/retarget the affected tests.

**Out of scope (later — Spec "design refactor"):** the dispatcher's service-locator
`ApplicationContext` usage, the renderer-selection / arg-injection OCP ladders, package-boundary
restructuring, `CLAUDE.md` renderer drift, and full interface-ization of every collaborator.
Starter conventions (validation, `enabled` flag, etc.) are their own separate spec.

## Background: current responsibilities of `CommandsSessionBot`

1. Update intake — `consume(Update)` → `updatesSink`.
2. Out-of-band publishing — `sendMessage(...)` → `messagesSink`.
3. Pipeline wiring — `@PostConstruct init()` builds one `Flux.concat(...).subscribe()`.
4. Per-chat fold/dispatch — `handleUpdates(Flux<UpdateWrapper>)`.
5. Message execution — `executeMessage(...)` (typed `TelegramClient` dispatch + media `switch`).
6. Lifecycle — `@PostConstruct` / `@PreDestroy`.

The current concurrency guarantees (must be preserved verbatim through the move): inner
`concatMap` per chat, outer `flatMap` over `groupBy(chatId)`, `publishOn(Schedulers.boundedElastic())`
per group, `updatesSink.emitNext(..., FAIL_FAST)`, `messagesSink.emitNext(..., busyLooping(1s))`,
per-stream `onErrorResume`, and `Disposable` disposed in `@PreDestroy`.

## Components

### `MessageExecutor` (interface) + `TelegramClientMessageExecutor` (impl)
- Interface: `<T extends Serializable> T execute(PartialBotApiMethod<T> message)`.
- Default impl wraps `TelegramClient`: the Java-21 pattern-matching `switch` over `BotApiMethod`
  + the 7 `Send*` media types, unknown `PartialBotApiMethod` → WARN + route through the error
  handler, all inside the `try/catch (TelegramApiException)`. (This is exactly today's
  `executeMessage`, lifted out.)
- Chosen as an interface because it is the likeliest custom-override point for a consuming app.

### `TelegramUpdateHandler`
- Public method `Flux<PartialBotApiMethod<?>> handleUpdates(Flux<UpdateWrapper> updates)` — the
  per-chat fold/dispatch: `scanWith(CommandContext::empty, fold)` → `skip(1)` → `concatMap` →
  empty→help, else auth-gate → `commandsFactory.getCommand(...).process(context)`, with the
  `doOnNext` that executes each emitted message via `MessageExecutor` and, in `progress` state,
  records the returned `Message` via `addQuestionMessage`.
- Dependencies: `CommandsFactory`, `AuthInterceptor`, `ErrorHandlerFactory`, `MessageExecutor`.
- Directly unit-testable: feed a `Flux<UpdateWrapper>` for one chat + a mocked `MessageExecutor`;
  no package-private seam on the bot is needed any more.

### `OutboundMessages`
- Owns `messagesSink = Sinks.many().unicast().onBackpressureBuffer()`.
- Public `void sendMessage(PartialBotApiMethod<?> message)` → `messagesSink.emitNext(message,
  EmitFailureHandler.busyLooping(Duration.ofSeconds(1)))`.
- Exposes `Flux<PartialBotApiMethod<?>> messages()` → `messagesSink.asFlux()`.
- Pure outbound queue; it does **not** execute. Consuming apps inject this bean (not the bot) to
  push out-of-band messages.

### `CommandsSessionBot` (thin coordinator)
- `implements LongPollingSingleThreadUpdateConsumer`.
- Owns `updatesSink`; `consume(Update)` → `updatesSink.emitNext(update, FAIL_FAST)` (+ the existing
  `log.debug` of the update id).
- Dependencies: `TelegramUpdateHandler`, `OutboundMessages`, `MessageExecutor`, `CommandsFactory`
  (for the startup command list). **No `CommandsSessionBotProperties`** — the coordinator no longer
  needs the token (it lives in the executor's client and in registration), so the previously-dead
  `properties` field is removed.
- `@PostConstruct init()` builds the subscriptions (below); `@PreDestroy` disposes the composite.
- No `sendMessage`, no `executeMessage`, no `handleUpdates` — all moved out.

## Execution / question-message coupling

The `progress`-state path must execute a prompt **and** feed the resulting `Message` back into the
`CommandContext` (`addQuestionMessage`, for later cleanup). Both the execution result and the
context live inside the fold, so `TelegramUpdateHandler` executes its own messages via the injected
`MessageExecutor` (today's `doOnNext` behavior, unchanged). Consequence — an intentional, documented
asymmetry: the **update** stream is executed inside the handler; the **out-of-band** and
**SetMyCommands** streams are executed by the coordinator. Unifying this would require redesigning
question-message tracking and is out of scope.

## Subscription model

`@PostConstruct init()` creates **three independent subscriptions**, each added to a
`Disposable.Composite` (a `Disposables.composite()`), all disposed in `@PreDestroy`:

1. **Startup commands:** the `SetMyCommands` mono (built from `commandsFactory.getCommands()`) →
   `.subscribe(messageExecutor::execute, errorLogger)`.
2. **Updates:** `updatesSink.asFlux().map(UpdateWrapper::wrap).groupBy(UpdateWrapper::getChatId)
   .flatMap(group -> updateHandler.handleUpdates(group.publishOn(Schedulers.boundedElastic()))
   .onErrorResume(error -> errorHandler.handle(error).doOnNext(messageExecutor::execute)))
   .subscribe(ignored -> {}, errorLogger)` — the handler executes its own messages inline, so this
   subscription only drives the stream and logs terminal errors.
3. **Out-of-band:** `outboundMessages.messages().publishOn(Schedulers.boundedElastic())
   .doOnNext(messageExecutor::execute).subscribe(ignored -> {}, errorLogger)`.

Notes: the unicast `.retry()` trap stays gone. `SetMyCommands` no longer strictly precedes updates
(independent subscriptions race) — acceptable for a one-off registration. Concurrent execution
across streams is safe (`TelegramClient`/OkHttp is thread-safe).

## Auto-config wiring (`CommandsSessionBotConfiguration`)

All new beans `@ConditionalOnMissingBean` so a consuming app can override them:
- `MessageExecutor messageExecutor(TelegramClient)` → `new TelegramClientMessageExecutor(client)`.
- `OutboundMessages outboundMessages()` → `new OutboundMessages()`.
- `TelegramUpdateHandler telegramUpdateHandler(CommandsFactory, AuthInterceptor, ErrorHandlerFactory,
  MessageExecutor)`.
- `CommandsSessionBot bot(TelegramUpdateHandler, OutboundMessages, MessageExecutor, CommandsFactory)`.
- `telegramClient` and `telegramBotsApplication` beans are unchanged (`registerBot(token, bot)` still
  registers the coordinator as the consumer).

## Testing

- **New `TelegramUpdateHandlerTest`** — the fold/dispatch behavior currently asserted via the bot's
  package-private `handleUpdates` moves here, driven directly through the public method with a mocked
  `MessageExecutor` (command starts fresh context, non-command appends, refreshContext rebuild,
  empty→help, auth-reject→`BotAuthException`, progress→`addQuestionMessage`, completed command
  executes its response).
- **New `MessageExecutorTest`** — the media-dispatch / `BotApiMethod` / unknown-type cases (moved
  from the bot test's media tests), verifying the typed `TelegramClient` overloads.
- **New `OutboundMessagesTest`** — `sendMessage` enqueues and `messages()` emits; concurrent
  `sendMessage` from multiple threads loses nothing (the busyLooping guarantee).
- **`CommandsSessionBotTest`** — retargeted to the coordinator: `consume` feeds the pipeline,
  per-chat ordering and failure-isolation hold end-to-end, `@PreDestroy` disposes. The package-private
  `handleUpdates` seam is removed (it's now a public method on the handler).
- Reuse the existing `Fixtures`/`OrderCommand`/`EchoCommand`/`FixtureCommandConfig`.

## The hard constraint

Behavior must be identical and **every Spec-2 concurrency guarantee preserved** (`concatMap`,
`publishOn(boundedElastic)`, `emitNext` FAIL_FAST/busyLooping, per-stream `onErrorResume`, lifecycle
disposal) — just relocated into the new units. The merged Spec-1 + Spec-2 test suites are the
regression net; the retargeted tests above must stay green.

## Risks

- **Lost concurrency guarantee during the move** — the highest risk; mitigated by the concurrency
  tests (ordering/contention/failure-isolation) which must remain green after relocation.
- **Public API change** — `sendMessage` moves off `CommandsSessionBot` onto `OutboundMessages`;
  acceptable for this pre-1.0 library, but it's a breaking change for any consumer calling
  `bot.sendMessage(...)`.
- **Execution asymmetry** (handler-executes vs coordinator-executes) is intentional; documented so a
  future reader doesn't "fix" it into a double-execution bug.