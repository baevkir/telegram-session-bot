# Design: Runtime correctness & concurrency (Spec 2 of 4)

**Date:** 2026-06-02
**Branch:** `runtime-correctness-concurrency` (off master)
**Status:** Approved design, ready for implementation plan

## Where this sits

Second of the four-spec program agreed after the multi-lens review:

1. ✅ Characterization test suite (merged) — the regression net.
2. **Runtime correctness & concurrency** ← this spec (groups A + B + E).
3. Starter conventions (group D).
4. Design refactor (group C).

The Spec 1 suite is the safety net for the changes here. One group-B item — completed
commands not executing their responses — was already fixed ahead of schedule and is **not**
re-addressed here.

## Goal

Make `CommandsSessionBot` and the dispatch path thread-safe and robust under realistic
framework usage (async handlers, off-thread `sendMessage`, blocking Telegram I/O), fix the
remaining correctness defects, and clean up logging — without changing the framework's
public command/dispatch contract.

## Scope

**In scope:** the concurrency hardening (A), the nine remaining correctness fixes (B), and
the logging hygiene (E) detailed below.

**Out of scope (later specs):** starter conventions (D — validation, `enabled` flag,
conditional beans, BOM), the structural design refactor (C — dispatcher service-locator,
OCP strategy ladders, package boundaries). The `CLAUDE.md` renderer drift
(`@RenderingMethod`/`createChild`) is left for Spec 4.

**Already done (not here):** the completed-command execution fix.

## A — Concurrency hardening

(Carried forward from the previously approved concurrency design; threading model =
per-chat sequential, cross-chat parallel.)

1. **Per-chat ordering** — in `handleUpdates`, the inner `.flatMap(context → …)` becomes
   `.concatMap(context → …)`. Within a chat's group this serializes context processing in
   arrival order, eliminating concurrent mutation of the shared `CommandContext`. The outer
   `flatMap` over `groupBy(chatId)` stays `flatMap` (distinct chats remain concurrent).
   `CommandContext` internals are left unchanged — `concatMap` + Reactor happens-before make
   single-chat access safe without locks.
2. **Scheduling** — apply `publishOn(Schedulers.boundedElastic())` to each group's chain so
   the single poll/consumer thread only feeds the sink; dispatch and blocking
   `telegramClient.execute(...)` run on `boundedElastic` workers; different chats run on
   different workers; order is preserved within a chat.
3. **Safe sink emission** — replace the ignored `tryEmitNext` calls:
   `updatesSink.emitNext(update, EmitFailureHandler.FAIL_FAST)` (single producer; surfaces
   failures instead of silently dropping) and
   `messagesSink.emitNext(message, EmitFailureHandler.busyLooping(Duration.ofSeconds(1)))`
   (makes the public, multi-thread `sendMessage` safe).
4. **Remove the unicast-`retry` trap** — delete the top-level `.retry()` above the
   unicast-sink-sourced flux (resubscribing a unicast sink throws). Per-group
   `onErrorResume` already contains a single chat's failure.
5. **Lifecycle** — `init()` keeps the `Disposable` from `subscribe(...)`, passes an
   error-logging consumer, and a `@PreDestroy` disposes it on shutdown.

## B — Correctness fixes

Each fix lands TDD red→green with the test Spec 1 deferred.

1. **`executeMessage` media support** — convert the `instanceof BotApiMethod` check to a
   Java-21 pattern-matching `switch`: the `BotApiMethod` case calls
   `telegramClient.execute((BotApiMethod<T>) m)` as today; add cases for `SendPhoto`,
   `SendDocument`, `SendVideo`, `SendAudio`, `SendVoice`, `SendSticker`, `SendAnimation`
   dispatching to `TelegramClient`'s typed `execute` overloads (each returns `Message`; cast
   to `T`). An unknown `PartialBotApiMethod` is logged at WARN and routed through the error
   handler rather than throwing a bare `UnsupportedOperationException`.
2. **Auth-path NPE** (`CommandsSessionBot`) — null-guard `getFrom()` (and username) when
   composing the `BotAuthException` message so a missing user doesn't replace the auth
   rejection with an NPE.
3. **UTF-8 callback-byte check** (`CommandBuilder`) — use
   `result.toString().getBytes(StandardCharsets.UTF_8).length` and a named constant
   `MAX_CALLBACK_BYTES = 64`; the warn message stays but logs the byte length, not the raw
   callback (see E).
4. **No-match routing** (`CommandsDispatcher`) — replace the bare `RuntimeException` for an
   unmatched command/args with a `BotCommandException` (carrying the `CommandContext`) so
   `ErrorHandlerFactory` routes it to `BotCommandErrorHandler` instead of swallowing it.
5. **Double `buildKeyboard`** (`AbstractMessagePresenter`) — in `buildMessage`, pass the
   already-computed `keyboard` local to the `InlineKeyboardMarkup` builder instead of
   calling `buildKeyboard(...)` a second time.
6. **`ErrorHandler` generic resolution** (`ErrorHandlerFactory.init`) — replace
   `errorHandler.getClass().getGenericInterfaces()[0]` casting with
   `org.springframework.core.GenericTypeResolver.resolveTypeArgument(handler.getClass(),
   ErrorHandler.class)`, which is robust to interface order and base classes.
7. **Duplicate `@CommandMethod` templates** (`MethodMatcher.create`) — detect a duplicate
   `arguments` template and throw an `IllegalStateException` naming the command class and the
   offending template, instead of the opaque `Collectors.toMap` key collision.
8. **Unmatched auto-injection parameter** (`CommandsDispatcher.invoke`) — when a
   non-`@Parameter` argument matches none of the supported auto-injection cases
   (`UpdateWrapper command`/`update`, `Update update`, `User from`, `String chatId`,
   `DynamicParameters`, `CommandContext`), throw a clear `BotCommandException` naming the
   parameter rather than silently under-filling the args array (which later fails opaquely
   at reflective invoke).
9. **`"null"` magic string** — extract a shared `CommandConstants.NULL_ANSWER = "null"` and
   use it in both `CommandBuilder` (null encoding) and `CommandsDispatcher.getArgument`
   (null decoding) so the convention lives in one place.

## E — Logging hygiene

- **Correlation** — add `chatId`/`updateId` as explicit SLF4J `{}` params on the key lines:
  `consume` (already logs `updateId`; add nothing more there), the dispatch line (already has
  both), and the error-handler / failure logs (add `chatId`).
- **PII trim** — `UpdateWrapper`'s "cannot get chat id" ERROR logs the update id and type,
  not the full `Update`; `CommandBuilder`'s over-limit WARN logs the byte length, not the raw
  callback string.
- **Constant template** — `BotCommandErrorHandler` uses a constant message with the detail as
  a parameter: `log.error("Command failed: {}", botMessage, exception)` (never the
  user/root-cause string as the template).
- **Throwables** — `CommandsDispatcher`'s invocation-failure debug passes the `error`
  throwable as the last arg (stack trace preserved), not `error.toString()`.
- **Levels** — `BotAuthErrorHandler` logs an expected auth rejection at WARN without a stack
  trace, including the chat id / username for context.

## Testing

- **Concurrency (A)** — the guarantee tests Spec 1 deferred: (a) per-chat updates process in
  arrival order under `concatMap`; (b) concurrent `sendMessage` from multiple threads loses
  no message (validates `busyLooping`); (c) a chat whose handler errors does not terminate
  the stream (per-group `onErrorResume` + no top-level retry). `StepVerifier` + a small
  thread pool.
- **Correctness (B)** — one focused test per fix: media `execute` dispatch (mock
  `TelegramClient` verified for `SendPhoto`/`SendDocument`), auth-message NPE guard, UTF-8
  byte boundary, no-match → `BotCommandException` routed by `ErrorHandlerFactory`, single
  `buildKeyboard` invocation/correct output, `GenericTypeResolver` handler registration,
  duplicate-template clear error, unmatched-parameter clear error.
- **Logging (E)** — light: the constant-template error handler tolerates a `botMessage`
  containing `{}` without throwing/misformatting.

## Risks

- **Scheduling correctness** — `publishOn` placement relative to `groupBy`/`concatMap` must
  be exact; covered by the ordering test and review.
- **Media `execute` casts** — the typed `execute` overloads return `Message`; the `(T)` casts
  are unchecked but safe because those send types are `PartialBotApiMethod<Message>`. The
  pattern `switch` must stay exhaustive enough to route unknowns to the WARN/error path.
- **Behaviour parity** — moving execution onto `boundedElastic` changes the thread context
  for downstream handlers; reactive handlers must not assume a thread. Note in `CLAUDE.md`.
- **Plan size** — A+B+E is large; the implementation plan will be phased and may split into
  two plans (concurrency vs. correctness+logging).