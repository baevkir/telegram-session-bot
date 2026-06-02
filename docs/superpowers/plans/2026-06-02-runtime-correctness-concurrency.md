# Runtime Correctness & Concurrency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `CommandsSessionBot` and the dispatch path thread-safe and robust under realistic framework usage (async handlers, off-thread `sendMessage`, blocking Telegram I/O), fix the nine remaining correctness defects, and clean up logging — without changing the framework's public command/dispatch contract. Implements groups A (concurrency), B (correctness), and E (logging hygiene) of the Spec-2 design. The completed-command execution bug is **already fixed** and is not re-addressed.

**Architecture:** The bot is a reactive Reactor pipeline. `consume(Update)` feeds a unicast `updatesSink`; `init()` builds a flux that wraps updates, `groupBy(chatId)` into per-chat streams, folds each stream into an evolving `CommandContext` via `scanWith`, and dispatches the matched command. A separate unicast `messagesSink` (fed by the public `sendMessage`) merges in out-of-band messages. Results (`PartialBotApiMethod`) are executed against a `TelegramClient`. This plan hardens that pipeline (per-chat ordering via `concatMap`, off-thread scheduling, safe sink emission, lifecycle management) and fixes correctness/logging defects in the dispatcher, builders, presenters, and error handlers.

**Tech Stack:** Java 21, Maven, Spring Boot 3.5, Project Reactor (Flux/Mono/Sinks/Schedulers), `org.telegram:telegrambots*` 10.0.0, Lombok, JUnit 5 + AssertJ + Mockito + `reactor-test` (StepVerifier).

---

## Environment & conventions (apply to EVERY task)

- **JDK:** Build under Java 21. The machine's default Maven JDK may be 24, which crashes Lombok. Before any Maven command:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # ms-21.0.8 is installed
  ```
- **Automated gate (run it yourself):** `mvn clean test-compile` must pass for every task. For TDD tasks, also run the single new test class:
  ```bash
  mvn -q test -Dtest=<TestClass>
  ```
- **Owner-run acceptance:** The maintainer runs the full `mvn test` and per-class `mvn test -Dtest=<Class>` as acceptance. Do not assume the full suite is your gate; `mvn clean test-compile` + the focused new test is.
- **Commits:** one commit per task. Conventional messages (`fix:` / `refactor:` / `feat:` / `test:`). **No AI/Claude attribution trailers.** Never stage `.DS_Store`, `.claude/`, `target/`, or IDE files. Stage only the files the task touches.
- **TDD nuance:** B and E fixes are true red→green — write the failing test first, watch it fail (or fail to compile against the intended assertion), then make the minimal production change. The pure-refactor concurrency edits (A1/A2/A4) preserve behavior, so their "test" is the concurrency-guarantee tests in A6 plus `mvn clean test-compile`; commit A1/A2/A4 together with A3/A5 as coherent `init()`/`handleUpdates` changes, but keep the steps below bite-sized.
- **Reuse fixtures:** Reuse `com.kb.sessionbot.fixtures.{Fixtures, OrderCommand, EchoCommand, FixtureCommandConfig}` and extend the existing test classes (`CommandsSessionBotTest`, `CommandsDispatcherTest`, `MethodMatcherTest`, `ErrorHandlerFactoryTest`, `CommandBuilderTest`) rather than duplicating setup. Add new fixture commands only where a new defect needs a trigger that no existing fixture provides.

---

## File Structure

| File | Modified / Created | Responsibility in this plan |
| --- | --- | --- |
| `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` | Modified | A1–A5 concurrency; B1 media `execute` switch; B2 auth NPE guard; E correlation/throwable logging |
| `src/main/java/com/kb/sessionbot/commands/CommandBuilder.java` | Modified | B3 UTF-8 `MAX_CALLBACK_BYTES`; B9 `NULL_ANSWER` encoding; E PII-trim WARN |
| `src/main/java/com/kb/sessionbot/commands/CommandConstants.java` | Modified | B3 `MAX_CALLBACK_BYTES`; B9 `NULL_ANSWER` |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcher.java` | Modified | B4 no-match → `BotCommandException`; B8 unmatched auto-injection param; B9 `NULL_ANSWER` decoding; E throwable-not-toString |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/MethodMatcher.java` | Modified | B7 duplicate `@CommandMethod` template clear error |
| `src/main/java/com/kb/sessionbot/commands/presenter/AbstractMessagePresenter.java` | Modified | B5 double `buildKeyboard` |
| `src/main/java/com/kb/sessionbot/errors/handler/ErrorHandlerFactory.java` | Modified | B6 `GenericTypeResolver` handler registration |
| `src/main/java/com/kb/sessionbot/errors/handler/BotCommandErrorHandler.java` | Modified | E constant-template logging |
| `src/main/java/com/kb/sessionbot/errors/handler/BotAuthErrorHandler.java` | Modified | E ERROR→WARN, no stack trace, context |
| `src/main/java/com/kb/sessionbot/model/UpdateWrapper.java` | Modified | E PII-trim "cannot get chat id" log |
| `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` | Modified | A6 concurrency tests; B1 media; B2 auth NPE guard |
| `src/test/java/com/kb/sessionbot/commands/CommandBuilderTest.java` | Modified | B3 UTF-8 byte boundary |
| `src/test/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcherTest.java` | Modified | B4 no-match routing; B8 unmatched param; B9 null decoding |
| `src/test/java/com/kb/sessionbot/commands/dispatcher/MethodMatcherTest.java` | Modified | B7 duplicate-template error |
| `src/test/java/com/kb/sessionbot/errors/handler/ErrorHandlerFactoryTest.java` | Modified | B6 GenericTypeResolver via base-class handler; E `{}`-tolerant template |
| `src/test/java/com/kb/sessionbot/commands/presenter/AbstractMessagePresenterTest.java` | Created | B5 single `buildKeyboard` invocation / correct output |
| `src/test/java/com/kb/sessionbot/fixtures/BadInjectionCommand.java` | Created | B8 fixture: a `@CommandMethod` with an unsupported auto-injected param |

---

## Confirmed facts from source (verified — do not re-derive)

**`CommandsSessionBot` (current shape, telegrambots 10.0.0):**
- Implements `LongPollingSingleThreadUpdateConsumer` (not `TelegramLongPollingBot`); the consumer entry point is `consume(Update)`.
- Fields: `updatesSink = Sinks.many().unicast().onBackpressureBuffer()` (type `Sinks.Many<Update>`), `messagesSink = Sinks.many().unicast().onBackpressureBuffer()` (type `Sinks.Many<PartialBotApiMethod<?>>`).
- `sendMessage` currently does `messagesSink.tryEmitNext(message)` (return ignored).
- `consume` currently does `updatesSink.tryEmitNext(update)` (return ignored); already logs `log.debug("Received update id={}", update.getUpdateId())`.
- `init()` is `@PostConstruct`, builds `Flux.concat(setMyCommands.doOnNext(this::executeMessage), updatesSink.asFlux().map(UpdateWrapper::wrap).groupBy(...).flatMap(...).mergeWith(messagesSink.asFlux().doOnNext(this::executeMessage)).retry()).subscribe();`
- `handleUpdates` ends in `.flatMap(context -> { ... })` (the inner per-chat operator). The per-chat dispatch `doOnNext` already executes unconditionally after the recent completed-command fix.
- The auth-rejection error message at line ~110 uses `context.getCommandUpdate().getFrom().getUserName()` — `getFrom()` can be null (see `UpdateWrapper.getFrom()` which `.orElse(null)`), so `.getUserName()` NPEs. This is **B2**.
- `executeMessage(PartialBotApiMethod<T>)` currently: `if (message instanceof BotApiMethod<T> botApiMethod) { return telegramClient.execute(botApiMethod); } throw new UnsupportedOperationException(...)` inside a `try/catch (TelegramApiException)`. This is **B1**.

**`TelegramClient` typed `execute` overloads (verified via `javap` against `telegrambots-meta-10.0.0.jar`):** all under package `org.telegram.telegrambots.meta.api.methods.send`, each returns `org.telegram.telegrambots.meta.api.objects.message.Message`:
```
Message execute(SendDocument)
Message execute(SendPhoto)
Message execute(SendVideo)
Message execute(SendSticker)
Message execute(SendAudio)
Message execute(SendVoice)
Message execute(SendAnimation)
```
Plus the generic `<T extends Serializable, Method extends BotApiMethod<T>> T execute(Method)`. (Other overloads exist — `SendMediaGroup`/`SendPaidMedia`→`List<Message>`, `SendVideoNote`/`SendLivePhoto`→`Message`, various `Boolean`/`File`/`Serializable` — but the spec's B1 scope is exactly the seven `Send*` types above, so only those are added.)

**`CommandConstants`** is an interface holding `String` constants (`COMMAND_START` etc.). New constants are added here. There is **no** existing `MAX_CALLBACK_BYTES` or `NULL_ANSWER`.

**`CommandBuilder.build()`** currently checks `result.toString().getBytes().length > 64` (platform-default charset) and logs the raw `result`. This is **B3** + **E**.

**`CommandsDispatcher`:**
- `getArgument(...)` decodes null via `if (answer.equals("null"))` — magic string, **B9** decode side.
- `invoke(...)` has a bare `throw new RuntimeException("Cannot find command method for ...")` at the `methodDescriptor == null && invocationArgument == null` branch — **B4**. (Note: `findInvokerMethod` always sets `invocationArgument` when it returns null, so this branch is currently effectively unreachable; converting it to `BotCommandException` is a defensive correctness fix per spec, plus a direct unit test on the produced exception type via a stubbed path is impractical — see B4 task for the testable seam.)
- The auto-injection `if/else if` ladder (lines ~106–120) has **no final `else`**: a non-`@Parameter` arg matching none of the supported cases is silently skipped, under-filling `args`, which later fails opaquely at reflective invoke — **B8**.
- The catch block logs `log.debug("Command '{}' invocation raised {}", commandId, error.toString())` — **E** (pass the throwable, not `toString()`).

**`MethodMatcher.create(...)`** collects methods via `Collectors.toMap(MethodDescriptor::getArguments, Function.identity())` — duplicate `arguments` templates throw the opaque `IllegalStateException: Duplicate key ...` from `toMap`. **B7** replaces this with a named-class/template error.

**`AbstractMessagePresenter.buildMessage`** computes `var keyboard = buildKeyboard(source, context)` then, inside the `if (keyboard != null)`, calls `buildKeyboard(source, context)` **again** when building the markup — **B5**. (`buildEditMessage` already correctly reuses the local `keyboard`.)

**`ErrorHandlerFactory.init()`** resolves the handler's exception type via `((ParameterizedType) errorHandler.getClass().getGenericInterfaces()[0]).getActualTypeArguments()[0]` — brittle to interface order / base classes. **B6** uses `org.springframework.core.GenericTypeResolver.resolveTypeArgument(handler.getClass(), ErrorHandler.class)`. `spring-core` is on the classpath transitively via `spring-boot-starter`. `ErrorHandler<E>` is the interface; handlers implement `ErrorHandler<BotCommandException>` etc.

**`BotCommandErrorHandler`** logs `log.error(botMessage, exception)` where `botMessage` is the root-cause message used **as the SLF4J template** — a `{}` in a user/root-cause message misformats. **E** uses `log.error("Command failed: {}", botMessage, exception)`.

**`BotAuthErrorHandler`** logs `log.error("Authentication failure", exception)` — an expected rejection logged at ERROR with stack trace. **E** → WARN, no stack trace, include chat id / username.

**`UpdateWrapper.getChatId()`** error branch logs `log.error("Cannot get chat id from update.{}", update)` (full `Update` = PII). **E** logs update id + type instead. `Update` exposes `getUpdateId()`; for "type", use the boolean predicates already on `Update` (`hasMessage()`, `hasCallbackQuery()`, etc.) — emit a short descriptor string.

**`BotCommandException(CommandContext context, Throwable cause)`** and **`BotAuthException(CommandContext context, String message)`** are the constructors. `BotCommandException` carries the context via `getContext()`.

**`CommandContext`** is per-chat session state; `getChatId()` returns `String`. Its `answers`/`questionMessages` are already `Collections.synchronizedList`. Under `concatMap` a single chat's context is accessed sequentially (Reactor happens-before), so no further locking is needed.

**Test infra:** `Fixtures.messageUpdate(updateId, chatId, messageId, text)`, `Fixtures.callbackUpdate(updateId, chatId, questionMessageId, data)`, `Fixtures.wrap(update)`, `Fixtures.message(chatId, messageId, text)`, `Fixtures.CHAT_ID = 4242L`. `CommandsSessionBotTest` already mocks `TelegramClient` and stubs `execute(any(BotApiMethod.class))` to return a `Message`. `handleUpdates` is package-visible and directly testable with a `Flux<UpdateWrapper>`.

---

## PHASE A — Concurrency hardening

All A-edits land in `CommandsSessionBot.java`. A1–A5 are coherent edits to `consume`/`sendMessage`/`init()`/`handleUpdates` plus a new `@PreDestroy`. Implement the production edits (A1–A5) first as one focused change set, then add the guarantee tests (A6). Gate: `mvn clean test-compile`, then the A6 tests.

### Task A1 — Per-chat ordering: inner `flatMap` → `concatMap`

**Files:** `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- [ ] In `handleUpdates`, change the inner `.flatMap(context -> { ... })` to `.concatMap(context -> { ... })`. The body is unchanged. This serializes context processing within a chat group in arrival order, removing concurrent mutation of the shared `CommandContext`.

**Before (tail of `handleUpdates`):**
```java
            .skip(1)
            .flatMap(context -> {
                if (context.isEmpty()) {
```
**After:**
```java
            .skip(1)
            .concatMap(context -> {
                if (context.isEmpty()) {
```

(No other change in `handleUpdates` for A1. The outer `groupBy(...).flatMap(...)` in `init()` stays `flatMap` so distinct chats remain concurrent.)

### Task A2 — Per-group scheduling on `boundedElastic`

**Files:** `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- [ ] Add `import reactor.core.scheduler.Schedulers;`.
- [ ] In `init()`, apply `publishOn(Schedulers.boundedElastic())` to each group's chain so the single poll/consumer thread only feeds the sink while dispatch + blocking `telegramClient.execute(...)` run on `boundedElastic` workers. Place `publishOn` on the grouped flux **before** `handleUpdates` so the per-chat `concatMap` (and the `executeMessage` side effects inside it) run on the worker; different chats get different workers; order within a chat is preserved.

**Before (the `groupBy` chain in `init()`):**
```java
                updatesSink.asFlux()
                    .map(UpdateWrapper::wrap)
                    .groupBy(UpdateWrapper::getChatId)
                    .flatMap(updates -> this.handleUpdates(updates).onErrorResume(error -> errorHandler.handle(error).doOnNext(this::executeMessage)))
                    .mergeWith(messagesSink.asFlux().doOnNext(this::executeMessage))
                    .retry()
```
**After:**
```java
                updatesSink.asFlux()
                    .map(UpdateWrapper::wrap)
                    .groupBy(UpdateWrapper::getChatId)
                    .flatMap(updates -> this.handleUpdates(updates.publishOn(Schedulers.boundedElastic()))
                        .onErrorResume(error -> errorHandler.handle(error).doOnNext(this::executeMessage)))
                    .mergeWith(messagesSink.asFlux().publishOn(Schedulers.boundedElastic()).doOnNext(this::executeMessage))
```
(`.retry()` removed here is A4; see that task. The `mergeWith` branch also moves to `boundedElastic` so out-of-band `sendMessage` execution doesn't run on the consumer thread.)

### Task A3 — Safe sink emission

**Files:** `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- [ ] Add `import reactor.core.publisher.Sinks.EmitFailureHandler;` (or reference `Sinks.EmitFailureHandler` fully) and `import java.time.Duration;`.
- [ ] `consume`: replace `updatesSink.tryEmitNext(update);` with `updatesSink.emitNext(update, Sinks.EmitFailureHandler.FAIL_FAST);` (single producer — surfaces failures instead of silently dropping).
- [ ] `sendMessage`: replace `messagesSink.tryEmitNext(message);` with `messagesSink.emitNext(message, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));` (public multi-thread entry — `busyLooping` makes concurrent emission safe).

**Before:**
```java
    public void sendMessage(PartialBotApiMethod<?> message) {
        messagesSink.tryEmitNext(message);
    }

    @Override
    public void consume(Update update) {
        log.debug("Received update id={}", update.getUpdateId());
        updatesSink.tryEmitNext(update);
    }
```
**After:**
```java
    public void sendMessage(PartialBotApiMethod<?> message) {
        messagesSink.emitNext(message, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
    }

    @Override
    public void consume(Update update) {
        log.debug("Received update id={}", update.getUpdateId());
        updatesSink.emitNext(update, Sinks.EmitFailureHandler.FAIL_FAST);
    }
```

### Task A4 — Remove the unicast `.retry()` trap

**Files:** `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- [ ] Delete the top-level `.retry()` from the `Flux.concat(...)` argument in `init()` (already shown removed in A2's "After"). Resubscribing a unicast-sink-sourced flux throws; per-group `onErrorResume` already contains a single chat's failure. No replacement operator is added.

### Task A5 — Lifecycle: store `Disposable`, error-logging subscriber, `@PreDestroy`

**Files:** `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- [ ] Add `import reactor.core.Disposable;` and `import jakarta.annotation.PreDestroy;` (PostConstruct import already present).
- [ ] Add field `private Disposable subscription;`.
- [ ] In `init()`, assign the subscription and pass an error-logging consumer to `subscribe`.
- [ ] Add a `@PreDestroy` method that disposes the subscription if non-null and not already disposed.

**Before (subscribe call in `init()`):**
```java
            ).subscribe();
    }
```
**After:**
```java
            ).subscribe(
                message -> { },
                error -> log.error("Bot pipeline terminated unexpectedly", error)
            );
    }

    @PreDestroy
    public void shutdown() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
```
- [ ] Change the start of the subscribe expression so the result is assigned:
**Before:**
```java
        Flux.concat(
```
**After:**
```java
        this.subscription = Flux.concat(
```

### Task A6 — Concurrency guarantee tests

**Files:** `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java`

- [ ] Add a `@Nested` class `Concurrency` to the existing test (reusing its `bot(...)`, `ALLOW`, `telegramClient` mock, fixtures). Add the imports listed below to the file's import block.
- [ ] **Test 1 — per-chat ordering under `concatMap`:** drive a chat through a multi-answer command and assert the emitted texts appear in arrival order. Use `handleUpdates` directly (deterministic, no scheduler timing).
- [ ] **Test 2 — concurrent `sendMessage` loses nothing:** call `init()`, fire N `sendMessage` calls from a small fixed thread pool, and verify the client executes N times (validates `busyLooping`).
- [ ] **Test 3 — failure isolation:** a chat whose dispatch errors does not terminate the stream; a second chat's update still processes (validates per-group `onErrorResume` + no top-level `retry`).
- [ ] Run `mvn -q test -Dtest=CommandsSessionBotTest`.

**Imports to add to `CommandsSessionBotTest`:**
```java
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
```

**Test code (append inside the class):**
```java
    @org.junit.jupiter.api.Nested
    @DisplayName("concurrency guarantees")
    class Concurrency {

        @DisplayName("per-chat updates process in arrival order under concatMap")
        @Test
        void perChatOrderingIsPreserved() {
            var bot = bot(ALLOW);
            // Within one chat: first "/order?buy" prompts for product, then "book" completes -> "buy:book".
            var updates = Flux.just(
                Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
                Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book")));

            StepVerifier.create(bot.handleUpdates(updates)
                    .filter(m -> m instanceof SendMessage)
                    .map(m -> ((SendMessage) m).getText()))
                .expectNextMatches(text -> text.contains("product"))
                .expectNext("buy:book")
                .verifyComplete();
        }

        @DisplayName("concurrent sendMessage from multiple threads loses no message")
        @Test
        void concurrentSendMessageLosesNothing() throws Exception {
            var bot = bot(ALLOW);
            bot.init(); // SetMyCommands emitted once at startup

            int threads = 8;
            int perThread = 25;
            int total = threads * perThread;
            var pool = Executors.newFixedThreadPool(threads);
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            var sent = new AtomicInteger();
            try {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                bot.sendMessage(SendMessage.builder()
                                    .chatId(String.valueOf(Fixtures.CHAT_ID))
                                    .text("m" + sent.getAndIncrement())
                                    .build());
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            // SetMyCommands (1) + every sendMessage executed; busyLooping must not drop any.
            verify(telegramClient, timeout(5000).times(total + 1)).execute(any(BotApiMethod.class));
        }

        @DisplayName("a chat whose handler errors does not terminate the stream (failure isolation)")
        @Test
        void failureInOneChatDoesNotKillPipeline() throws Exception {
            var bot = bot(DENY); // auth denial makes the first chat's dispatch error
            bot.init();

            // chat A: auth-denied command -> BotAuthException routed by per-group onErrorResume.
            bot.consume(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book"));
            // chat B (different chatId): a help-routing empty update still processes afterwards.
            long otherChat = Fixtures.CHAT_ID + 1;
            bot.consume(Fixtures.messageUpdate(2, otherChat, 200, "anything"));

            // SetMyCommands + the auth error message + chat B's help response all execute;
            // the pipeline survived the first chat's error.
            verify(telegramClient, timeout(5000).atLeast(3)).execute(any(BotApiMethod.class));
        }
    }
```
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=CommandsSessionBotTest`. Commit: `refactor: harden CommandsSessionBot concurrency (concatMap, boundedElastic, safe sinks, lifecycle)`.

---

## PHASE B — Correctness fixes (TDD red→green each)

### Task B1 — `executeMessage` media support (FULL: 7 Send* types)

**Files:** `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` (test first), `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- [ ] **RED:** add a `@Nested` class `MediaExecution` to `CommandsSessionBotTest` that exercises `executeMessage` via the public path. `executeMessage` is `private`, so drive it through `sendMessage` + `init()` and verify the mock client receives the typed call. Stub the media overloads on the existing mock. Add imports:
```java
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.InputFile;
```
```java
    @org.junit.jupiter.api.Nested
    @DisplayName("media execution")
    class MediaExecution {

        @DisplayName("SendPhoto and SendDocument dispatch to the typed TelegramClient.execute overloads")
        @Test
        void mediaMethodsDispatchToTypedOverloads() throws Exception {
            var bot = bot(ALLOW);
            Mockito.when(telegramClient.execute(any(SendPhoto.class)))
                .thenReturn(Fixtures.message(Fixtures.CHAT_ID, 1, "photo"));
            Mockito.when(telegramClient.execute(any(SendDocument.class)))
                .thenReturn(Fixtures.message(Fixtures.CHAT_ID, 2, "doc"));
            bot.init();

            bot.sendMessage(SendPhoto.builder()
                .chatId(String.valueOf(Fixtures.CHAT_ID))
                .photo(new InputFile("file_id_photo"))
                .build());
            bot.sendMessage(SendDocument.builder()
                .chatId(String.valueOf(Fixtures.CHAT_ID))
                .document(new InputFile("file_id_doc"))
                .build());

            verify(telegramClient, timeout(5000)).execute(any(SendPhoto.class));
            verify(telegramClient, timeout(5000)).execute(any(SendDocument.class));
        }
    }
```
  Run `mvn -q test -Dtest=CommandsSessionBotTest` — fails because the current `executeMessage` throws `UnsupportedOperationException` for non-`BotApiMethod` types, so the media calls never reach the typed overloads.
- [ ] **GREEN:** rewrite `executeMessage` as a Java-21 pattern-matching `switch`. Add imports for the seven Send* types. Unknown `PartialBotApiMethod` is logged at WARN and routed through the error handler (return `null`) instead of throwing.

**Before:**
```java
    private <T extends Serializable> T executeMessage(PartialBotApiMethod<T> message) {
        try {
            if (message instanceof BotApiMethod<T> botApiMethod) {
                log.debug("Executing {}", botApiMethod.getClass().getSimpleName());
                return telegramClient.execute(botApiMethod);
            }
            throw new UnsupportedOperationException("Message type " + message.getClass().getSimpleName() + " is not supported yet");
        } catch (TelegramApiException e) {
            log.error("Cannot execute message", e);
            return null;
        }
    }
```
**After:**
```java
    @SuppressWarnings("unchecked")
    private <T extends Serializable> T executeMessage(PartialBotApiMethod<T> message) {
        try {
            log.debug("Executing {}", message.getClass().getSimpleName());
            return switch (message) {
                case BotApiMethod<?> botApiMethod -> (T) telegramClient.execute((BotApiMethod<T>) botApiMethod);
                case SendPhoto sendPhoto -> (T) telegramClient.execute(sendPhoto);
                case SendDocument sendDocument -> (T) telegramClient.execute(sendDocument);
                case SendVideo sendVideo -> (T) telegramClient.execute(sendVideo);
                case SendAudio sendAudio -> (T) telegramClient.execute(sendAudio);
                case SendVoice sendVoice -> (T) telegramClient.execute(sendVoice);
                case SendSticker sendSticker -> (T) telegramClient.execute(sendSticker);
                case SendAnimation sendAnimation -> (T) telegramClient.execute(sendAnimation);
                default -> {
                    log.warn("Unsupported message type {}; routing through error handler", message.getClass().getSimpleName());
                    errorHandler.handle(new UnsupportedOperationException(
                        "Message type " + message.getClass().getSimpleName() + " is not supported"))
                        .subscribe(this::executeMessage);
                    yield null;
                }
            };
        } catch (TelegramApiException e) {
            log.error("Cannot execute message in chat (type={})", message.getClass().getSimpleName(), e);
            return null;
        }
    }
```
  Imports to add to `CommandsSessionBot`:
```java
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.methods.send.SendSticker;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
```
  Note: the `(T)` casts are unchecked but safe — each Send* type is a `PartialBotApiMethod<Message>` and its typed `execute` returns `Message`. The `case BotApiMethod<?>` cast preserves the existing generic behavior.
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=CommandsSessionBotTest`. Commit: `feat: add media (SendPhoto/Document/Video/Audio/Voice/Sticker/Animation) execute support`.

### Task B2 — Auth-path NPE guard

**Files:** `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` (test first), `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- [ ] **RED:** add a test that drives an auth-denied command whose update has **no** `from` user, asserting the stream surfaces `BotAuthException` (not `NullPointerException`). A message built without a `from` reproduces the missing-user case. Add this `@Test` to `CommandsSessionBotTest`:
```java
    @DisplayName("auth rejection with a missing user surfaces BotAuthException, not NPE")
    @Test
    void authRejectWithMissingUserDoesNotNpe() {
        var bot = bot(DENY);
        var update = new org.telegram.telegrambots.meta.api.objects.Update();
        update.setUpdateId(1);
        update.setMessage(org.telegram.telegrambots.meta.api.objects.message.Message.builder()
            .messageId(100)
            .chat(org.telegram.telegrambots.meta.api.objects.chat.Chat.builder().id(Fixtures.CHAT_ID).type("private").build())
            .text("/order?buy&book")
            .build()); // no .from(...)
        var updates = Flux.just(Fixtures.wrap(update));

        StepVerifier.create(bot.handleUpdates(updates))
            .expectError(BotAuthException.class)
            .verify();
    }
```
  Run — fails with NPE because `context.getCommandUpdate().getFrom().getUserName()` dereferences a null `from`.
- [ ] **GREEN:** null-guard `getFrom()` and username when composing the `BotAuthException` message.

**Before:**
```java
                        if (!result) {
                            log.debug("Auth rejected for command '{}' in chat {}", context.getCommand(), context.getChatId());
                            return Flux.error(new BotAuthException(context, "User " + context.getCommandUpdate().getFrom().getUserName()+ " is unauthorized to use bot."));
                        }
```
**After:**
```java
                        if (!result) {
                            var from = context.getCommandUpdate().getFrom();
                            var username = from != null ? from.getUserName() : "unknown";
                            log.debug("Auth rejected for command '{}' in chat {} (user={})", context.getCommand(), context.getChatId(), username);
                            return Flux.error(new BotAuthException(context, "User " + username + " is unauthorized to use bot."));
                        }
```
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=CommandsSessionBotTest`. Commit: `fix: guard null user when composing auth rejection message`.

### Task B3 — UTF-8 callback-byte check + `MAX_CALLBACK_BYTES`

**Files:** `src/test/java/com/kb/sessionbot/commands/CommandBuilderTest.java` (test first), `src/main/java/com/kb/sessionbot/commands/CommandConstants.java`, `src/main/java/com/kb/sessionbot/commands/CommandBuilder.java`

- [ ] **RED:** add a test under the existing `ByteBoundary` nested class proving the boundary is measured in UTF-8 bytes (a multi-byte char counts as >1 byte). Add:
```java
        @Test
        void multiByteCharsCountAsUtf8Bytes() {
            // Cyrillic 'я' is 2 bytes in UTF-8. "/c?" is 3 bytes; 31 'я' = 62 bytes -> 65 total, over the 64 limit.
            var answer = "я".repeat(31);
            var wire = CommandBuilder.create().command("c").addAnswer(answer).build();
            assertThat(wire).isEqualTo("/c?" + answer);
            assertThat(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isEqualTo(65);
            // Same string under the platform default could miscount; the builder must use UTF-8 for its limit check.
        }
```
  (The build still returns the full string; the assertion documents the UTF-8 length. This test compiles/passes once the production check is UTF-8-based; it primarily pins the intended contract. The behavioral change is internal — the WARN threshold — so also keep the existing `over/under` tests green.)
- [ ] **GREEN — constant:** in `CommandConstants` add:
```java
    int MAX_CALLBACK_BYTES = 64;
```
- [ ] **GREEN — builder:** in `CommandBuilder.build()` measure UTF-8 bytes against `MAX_CALLBACK_BYTES` and log the byte length, not the raw callback (E PII-trim).

**Before:**
```java
        if (result.toString().getBytes().length > 64) {
          log.warn("Command length is greater then 64 bytes and cannot be applied to to callback data. "  + result);
        }
        return result.toString();
```
**After:**
```java
        var callback = result.toString();
        var byteLength = callback.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_CALLBACK_BYTES) {
            log.warn("Callback data is {} bytes, exceeding the {}-byte Telegram limit and cannot be used as callback data.",
                byteLength, MAX_CALLBACK_BYTES);
        }
        return callback;
```
  Add `import java.nio.charset.StandardCharsets;` to `CommandBuilder` (the `MAX_CALLBACK_BYTES` constant is already in scope via the existing `import static com.kb.sessionbot.commands.CommandConstants.*;`).
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=CommandBuilderTest`. Commit: `fix: measure callback-data limit in UTF-8 bytes via MAX_CALLBACK_BYTES`.

### Task B4 — No-match routing → `BotCommandException`

**Files:** `src/test/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcherTest.java` (test first), `src/main/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcher.java`

- [ ] **GREEN production change:** replace the bare `RuntimeException` in `invoke(...)` with a `BotCommandException` carrying the context, so `ErrorHandlerFactory` routes it to `BotCommandErrorHandler`.

**Before:**
```java
            var methodDescriptor = findInvokerMethod(context, invocationResult);
            if (methodDescriptor == null) {
                if (invocationResult.invocationArgument != null) {
                    return invocationResult;
                }
                throw new RuntimeException(
                    "Cannot find command method for " + context.getCommand() + " with arguments " + context.getAnswers()
                );
            }
```
**After:**
```java
            var methodDescriptor = findInvokerMethod(context, invocationResult);
            if (methodDescriptor == null) {
                if (invocationResult.invocationArgument != null) {
                    return invocationResult;
                }
                throw new BotCommandException(context, new IllegalStateException(
                    "Cannot find command method for " + context.getCommand() + " with arguments " + context.getAnswers()));
            }
```
  This `throw` is caught by the existing `catch (Throwable error)` block, which wraps non-`BotCommandException` throwables in a new `BotCommandException` — but a `BotCommandException` thrown here would be **double-wrapped**. Refine the catch to avoid re-wrapping:
**Before:**
```java
        } catch (Throwable error) {
            log.debug("Command '{}' invocation raised {}", commandId, error.toString());
            invocationResult.invocationError = new BotCommandException(context, error);
        }
```
**After (also satisfies E throwable-not-toString):**
```java
        } catch (BotCommandException error) {
            log.debug("Command '{}' invocation failed in chat {}", commandId, context.getChatId(), error);
            invocationResult.invocationError = error;
        } catch (Throwable error) {
            log.debug("Command '{}' invocation failed in chat {}", commandId, context.getChatId(), error);
            invocationResult.invocationError = new BotCommandException(context, error);
        }
```
  `BotCommandException(context, error)` already exists; `getContext().getChatId()` is the chat id. `BotCommandException` import is already present.
- [ ] **Test (red→green seam):** the existing `NoMatch.rendersDefaultPromptAndNoInvocation` covers the path where `findInvokerMethod` sets `invocationArgument` (so the bare-throw branch is not hit). The bare-throw branch is exercised when `findInvokerMethod` returns null **and** leaves `invocationArgument` null — which the default renderer never does. To get a true, observable red→green, route a dispatcher invocation error end-to-end through `ErrorHandlerFactory` and assert it produces a `SendMessage` (i.e. is *handled*, not swallowed). Add to `CommandsDispatcherTest`:
```java
    @org.junit.jupiter.api.Nested
    @DisplayName("invocation errors route to BotCommandException")
    class ErrorRouting {

        @Test
        void invocationErrorIsWrappedAsBotCommandException() {
            // qty binds a Long; a non-numeric answer makes Jackson conversion fail inside the
            // reactive invocation, which onErrorMap wraps as BotCommandException carrying the context.
            var result = orderDispatcher.invoke(ctx("/order?qty&not-a-number"));
            assertThat(result.hasErrors()).isFalse(); // failure is deferred into the publisher
            StepVerifier.create(result.getInvocation())
                .expectError(com.kb.sessionbot.errors.exception.BotCommandException.class)
                .verify();
        }

        @Test
        void routedThroughErrorHandlerFactoryProducesSendMessage() {
            var factory = new com.kb.sessionbot.errors.handler.ErrorHandlerFactory(
                java.util.List.<com.kb.sessionbot.errors.handler.ErrorHandler<?>>of(
                    new com.kb.sessionbot.errors.handler.BotCommandErrorHandler(),
                    new com.kb.sessionbot.errors.handler.BotAuthErrorHandler()));
            factory.init();
            var ex = new com.kb.sessionbot.errors.exception.BotCommandException(
                ctx("/order?unsupported"),
                new IllegalStateException("Cannot find command method for order with arguments [unsupported]"));
            StepVerifier.create(factory.handle(ex))
                .assertNext(m -> assertThat(m).isInstanceOf(SendMessage.class))
                .verifyComplete();
        }
    }
```
  Add `import reactor.test.StepVerifier;` if not already present (it is) and `import org.telegram.telegrambots.meta.api.methods.send.SendMessage;` (already present). These assert the *contract* B4 establishes: dispatcher failures arrive as `BotCommandException` and are routed (not swallowed). The production `throw` change is the minimal defensive fix the spec calls for; `mvn clean test-compile` guards its compilation.
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=CommandsDispatcherTest`. Commit: `fix: route unmatched-command dispatch failures as BotCommandException`.

### Task B5 — Double `buildKeyboard` in `AbstractMessagePresenter`

**Files:** `src/test/java/com/kb/sessionbot/commands/presenter/AbstractMessagePresenterTest.java` (created, test first), `src/main/java/com/kb/sessionbot/commands/presenter/AbstractMessagePresenter.java`

- [ ] **RED:** create a test with a concrete `AbstractMessagePresenter` subclass that counts `buildKeyboard` invocations and asserts the keyboard is built exactly once per `buildMessage`, and that the produced `SendMessage` carries that keyboard.
```java
package com.kb.sessionbot.commands.presenter;

import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.model.CommandContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractMessagePresenterTest {

    private final AtomicInteger keyboardCalls = new AtomicInteger();

    private final AbstractMessagePresenter<String> presenter = new AbstractMessagePresenter<>() {
        @Override
        protected String buildText(String source, CommandContext context) {
            return "text:" + source;
        }

        @Override
        protected List<InlineKeyboardRow> buildKeyboard(String source, CommandContext context) {
            keyboardCalls.incrementAndGet();
            var row = new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("btn").callbackData("cb").build());
            return List.of(row);
        }
    };

    @Test
    @DisplayName("buildMessage builds the keyboard exactly once and attaches it")
    void buildMessageBuildsKeyboardOnce() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order?buy"));
        StepVerifier.create(reactor.core.publisher.Flux.from(presenter.buildMessage("hello", context)))
            .assertNext(method -> {
                assertThat(method).isInstanceOf(SendMessage.class);
                var send = (SendMessage) method;
                assertThat(send.getText()).isEqualTo("text:hello");
                assertThat(send.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
                assertThat(((InlineKeyboardMarkup) send.getReplyMarkup()).getKeyboard()).hasSize(1);
            })
            .verifyComplete();
        assertThat(keyboardCalls.get()).isEqualTo(1);
    }
}
```
  Run — fails: `keyboardCalls` is 2 because `buildMessage` calls `buildKeyboard` twice.
- [ ] **GREEN:** in `buildMessage`, pass the already-computed `keyboard` local to the markup builder.

**Before:**
```java
            var keyboard = buildKeyboard(source, context);
            if (keyboard != null) {
                builder.replyMarkup(InlineKeyboardMarkup.builder().keyboard(buildKeyboard(source, context)).build());
            }
```
**After:**
```java
            var keyboard = buildKeyboard(source, context);
            if (keyboard != null) {
                builder.replyMarkup(InlineKeyboardMarkup.builder().keyboard(keyboard).build());
            }
```
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=AbstractMessagePresenterTest`. Commit: `fix: build inline keyboard once in AbstractMessagePresenter.buildMessage`.

### Task B6 — `ErrorHandler` generic resolution via `GenericTypeResolver`

**Files:** `src/test/java/com/kb/sessionbot/errors/handler/ErrorHandlerFactoryTest.java` (test first), `src/main/java/com/kb/sessionbot/errors/handler/ErrorHandlerFactory.java`

- [ ] **RED:** add a test that registers a handler whose `ErrorHandler<E>` type argument comes from a **superclass** (not directly `getGenericInterfaces()[0]`), proving the brittle reflection fails and `GenericTypeResolver` succeeds. Add a base class + subclass inside the test:
```java
    abstract static class BaseCommandHandler implements ErrorHandler<BotCommandException> { }

    static class SubclassedCommandHandler extends BaseCommandHandler {
        @Override
        public reactor.core.publisher.Mono<? extends org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod<?>> handle(BotCommandException exception) {
            return reactor.core.publisher.Mono.fromSupplier(() ->
                SendMessage.builder().chatId(exception.getContext().getChatId()).text("handled-by-subclass").build());
        }
    }

    @Test
    @DisplayName("resolves the exception type from a handler that declares ErrorHandler on a superclass")
    void resolvesTypeArgumentThroughSuperclass() {
        var factory = new ErrorHandlerFactory(List.<ErrorHandler<?>>of(new SubclassedCommandHandler()));
        factory.init();
        var ex = new BotCommandException(context, new IllegalStateException("boom"));
        StepVerifier.create(factory.handle(ex))
            .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("handled-by-subclass"))
            .verifyComplete();
    }
```
  Run — fails: `SubclassedCommandHandler.getClass().getGenericInterfaces()` is empty (it extends a class, implements no interface directly), so the old reflection throws `ArrayIndexOutOfBoundsException`.
- [ ] **GREEN:** replace the reflection in `init()`.

**Before:**
```java
    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        errorHandlers.forEach(errorHandler -> {
            Class<Throwable> type = ((Class<Throwable>) ((ParameterizedType) errorHandler.getClass().getGenericInterfaces()[0]).getActualTypeArguments()[0]);
            errorHandlerMap.put(type, (ErrorHandler<Throwable>) errorHandler);
        });
    }
```
**After:**
```java
    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        errorHandlers.forEach(errorHandler -> {
            Class<?> type = GenericTypeResolver.resolveTypeArgument(errorHandler.getClass(), ErrorHandler.class);
            if (type == null) {
                log.warn("Cannot resolve exception type for handler {}; skipping registration", errorHandler.getClass().getName());
                return;
            }
            errorHandlerMap.put((Class<Throwable>) type, (ErrorHandler<Throwable>) errorHandler);
        });
    }
```
  Replace `import java.lang.reflect.ParameterizedType;` with `import org.springframework.core.GenericTypeResolver;`.
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=ErrorHandlerFactoryTest`. Commit: `fix: resolve ErrorHandler exception type with GenericTypeResolver`.

### Task B7 — Duplicate `@CommandMethod` template clear error

**Files:** `src/test/java/com/kb/sessionbot/commands/dispatcher/MethodMatcherTest.java` (test first), `src/main/java/com/kb/sessionbot/commands/dispatcher/MethodMatcher.java`

- [ ] **RED:** add a nested test with a local command class declaring two `@CommandMethod` methods with the same `arguments` template, asserting `MethodMatcher.create(...)` throws `IllegalStateException` whose message names the command class and the duplicate template.
```java
    @Nested
    @DisplayName("duplicate template detection")
    class DuplicateTemplates {

        @com.kb.sessionbot.commands.dispatcher.annotations.BotCommand(value = "dup", description = "dup")
        static class DuplicateCommand {
            @com.kb.sessionbot.commands.dispatcher.annotations.CommandMethod(arguments = "buy&{x}")
            public org.telegram.telegrambots.meta.api.methods.send.SendMessage one(
                @com.kb.sessionbot.commands.dispatcher.annotations.Parameter("x") String x) {
                return org.telegram.telegrambots.meta.api.methods.send.SendMessage.builder().chatId("1").text(x).build();
            }
            @com.kb.sessionbot.commands.dispatcher.annotations.CommandMethod(arguments = "buy&{x}")
            public org.telegram.telegrambots.meta.api.methods.send.SendMessage two(
                @com.kb.sessionbot.commands.dispatcher.annotations.Parameter("x") String x) {
                return org.telegram.telegrambots.meta.api.methods.send.SendMessage.builder().chatId("1").text(x).build();
            }
        }

        @Test
        void duplicateTemplateThrowsNamingClassAndTemplate() {
            assertThatThrownBy(() -> MethodMatcher.create(new DuplicateCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DuplicateCommand")
                .hasMessageContaining("buy&{x}");
        }
    }
```
  Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;`. Run — fails: the message is the opaque `toMap` "Duplicate key ..." not naming the class.
- [ ] **GREEN:** replace the `Collectors.toMap(...)` terminal with an explicit accumulation that detects duplicates with a clear message.

**Before:**
```java
            .collect(Collectors.toMap(MethodDescriptor::getArguments, Function.identity()));

        return new MethodMatcher(methods);
    }
```
**After:**
```java
            .collect(Collectors.toMap(
                MethodDescriptor::getArguments,
                Function.identity(),
                (existing, duplicate) -> {
                    throw new IllegalStateException(String.format(
                        "Duplicate @CommandMethod template '%s' in %s",
                        existing.getArguments(), command.getClass().getName()));
                },
                LinkedHashMap::new));

        return new MethodMatcher(methods);
    }
```
  `LinkedHashMap` is in `java.util.*` (already imported via `import java.util.*;`). The merge function fires on key collision, naming class + template.
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=MethodMatcherTest`. Commit: `fix: clear error on duplicate @CommandMethod templates`.

### Task B8 — Unmatched auto-injection parameter clear error

**Files:** `src/test/java/com/kb/sessionbot/fixtures/BadInjectionCommand.java` (created), `src/test/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcherTest.java` (test first), `src/main/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcher.java`

- [ ] **RED — fixture:** create a fixture command with a `@CommandMethod` whose non-`@Parameter` argument is of an unsupported auto-injection type (e.g. `java.time.LocalDate when`), which matches none of the supported cases.
```java
package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.commands.dispatcher.annotations.BotCommand;
import com.kb.sessionbot.commands.dispatcher.annotations.CommandMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.LocalDate;

@BotCommand(value = "badinject", description = "Unsupported auto-injection fixture")
public class BadInjectionCommand {

    // 'when' is neither @Parameter-annotated nor a supported auto-injection type (Update/UpdateWrapper/User/String chatId/DynamicParameters/CommandContext).
    @CommandMethod(arguments = "go")
    public SendMessage go(LocalDate when) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("when:" + when).build();
    }
}
```
- [ ] **RED — test:** add a nested test in `CommandsDispatcherTest`. Register the fixture (construct the dispatcher directly — no Spring bean needed since it has no `@Parameter` renderers), and assert invocation yields a `BotCommandException` naming the parameter rather than a silent under-fill / opaque reflective failure.
```java
    @Nested
    @DisplayName("unsupported auto-injection parameter")
    class UnsupportedInjection {

        @Test
        void unmatchedParameterYieldsClearBotCommandException() {
            var dispatcher = new CommandsDispatcher(new com.kb.sessionbot.fixtures.BadInjectionCommand(), context);
            var result = dispatcher.invoke(ctx("/badinject?go"));
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getInvocationError())
                .isInstanceOf(com.kb.sessionbot.errors.exception.BotCommandException.class);
            assertThat(result.getInvocationError().getCause())
                .hasMessageContaining("when");
        }
    }
```
  Run — fails: today the param is silently skipped, `args` is under-filled, and the reflective invoke fails opaquely (or with a wrong-arg-count error) inside the deferred publisher, not as a clear `BotCommandException` at `invoke()` time with the param name.
- [ ] **GREEN:** add a final `else` to the auto-injection ladder that throws a `BotCommandException` naming the parameter. It is thrown inside the `try`, caught by the `catch (BotCommandException ...)` block from B4, and surfaced as `invocationError`.

**Before (end of the auto-injection ladder):**
```java
                } else if (DynamicParameters.class.equals(parameter.getParameterType())) {
                    args.add(context.getDynamicParams());
                } else if (CommandContext.class.equals(parameter.getParameterType())) {
                    args.add(context);
                }

            }
```
**After:**
```java
                } else if (DynamicParameters.class.equals(parameter.getParameterType())) {
                    args.add(context.getDynamicParams());
                } else if (CommandContext.class.equals(parameter.getParameterType())) {
                    args.add(context);
                } else {
                    throw new BotCommandException(context, new IllegalArgumentException(String.format(
                        "Cannot resolve parameter '%s' of type %s in command '%s'. "
                            + "Annotate it with @Parameter or use a supported auto-injection type "
                            + "(UpdateWrapper command/update, Update update, User from, String chatId, DynamicParameters, CommandContext).",
                        parameter.getName(), parameter.getParameterType().getName(), commandId)));
                }

            }
```
  `BotCommandException` is already imported. Ordering note: this task depends on B4's `catch (BotCommandException)` refinement being in place (so the thrown `BotCommandException` is not double-wrapped); B4 precedes B8.
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=CommandsDispatcherTest`. Commit: `fix: clear error for unsupported auto-injection parameter`.

### Task B9 — `NULL_ANSWER` shared constant

**Files:** `src/test/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcherTest.java` (assert decode), `src/main/java/com/kb/sessionbot/commands/CommandConstants.java`, `src/main/java/com/kb/sessionbot/commands/CommandBuilder.java`, `src/main/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcher.java`

- [ ] **Constant:** add to `CommandConstants`:
```java
    String NULL_ANSWER = "null";
```
- [ ] **Decode (CommandsDispatcher.getArgument):** use the constant instead of the literal `"null"`.

**Before:**
```java
                .map(answer -> {
                    if (answer.equals("null")) {
                        return null;
                    }
                   return mapper.convertValue(answer, parameter.getParameterType());
                });
```
**After:**
```java
                .map(answer -> {
                    if (CommandConstants.NULL_ANSWER.equals(answer)) {
                        return null;
                    }
                   return mapper.convertValue(answer, parameter.getParameterType());
                });
```
  Add `import com.kb.sessionbot.commands.CommandConstants;` to `CommandsDispatcher` (it already imports `com.kb.sessionbot.commands.CommandBuilder`).
- [ ] **Encode (CommandBuilder.addAnswer(LocalDate)):** today a null `LocalDate` adds a literal `null` to the list (`answers.add(null)`), which `String.join` renders as the text `"null"`. Make the null encoding explicit and centralized using the constant.

**Before:**
```java
    public CommandBuilder addAnswer(LocalDate answer) {
        if (answer == null) {
            answers.add(null);
        } else {
            answers.add(answer.format(DateTimeFormatter.ISO_DATE));
        }
        return this;
    }
```
**After:**
```java
    public CommandBuilder addAnswer(LocalDate answer) {
        if (answer == null) {
            answers.add(NULL_ANSWER);
        } else {
            answers.add(answer.format(DateTimeFormatter.ISO_DATE));
        }
        return this;
    }
```
  `NULL_ANSWER` is in scope via the existing static import `import static com.kb.sessionbot.commands.CommandConstants.*;`.
- [ ] **Test:** add a round-trip test in `CommandsDispatcherTest` proving a `null`-encoded optional answer decodes to a `null` argument. Reuse `OrderCommand.note` (required + optional). Add:
```java
    @Test
    @DisplayName("the NULL_ANSWER sentinel decodes to a null argument")
    void nullAnswerSentinelDecodesToNull() {
        // note&{required}&{optional}; optional supplied as the NULL_ANSWER sentinel -> bound as null.
        var result = orderDispatcher.invoke(ctx("/order?note&hello&" + com.kb.sessionbot.commands.CommandConstants.NULL_ANSWER));
        assertThat(result.hasErrors()).isFalse();
        StepVerifier.create(result.getInvocation())
            .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("note:hello/null"))
            .verifyComplete();
    }
```
  (Add this inside the existing `Optional` nested class or a new one; `ctx`, `orderDispatcher`, `StepVerifier`, `SendMessage` are in scope.)
- [ ] Also add a `CommandBuilderTest` assertion that a null `LocalDate` encodes to the sentinel:
```java
        @Test
        void nullLocalDateEncodesToNullAnswerSentinel() {
            assertThat(CommandBuilder.create().command("c").addAnswer((java.time.LocalDate) null).build())
                .isEqualTo("/c?null");
        }
```
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=CommandsDispatcherTest,CommandBuilderTest`. Commit: `refactor: centralize null-answer encoding via CommandConstants.NULL_ANSWER`.

---

## PHASE E — Logging hygiene

Most E items are folded into the B tasks that touch the same lines (B3 PII-trim WARN in CommandBuilder; B4 throwable-not-toString in CommandsDispatcher; B2 chatId in auth-reject debug). The remaining standalone E items are below.

### Task E1 — `BotCommandErrorHandler` constant template (+ `{}`-tolerance test)

**Files:** `src/test/java/com/kb/sessionbot/errors/handler/ErrorHandlerFactoryTest.java` (test first), `src/main/java/com/kb/sessionbot/errors/handler/BotCommandErrorHandler.java`

- [ ] **RED (light):** add a test proving the handler tolerates a `botMessage` containing `{}` without throwing or misformatting — i.e. the emitted `SendMessage` text is exactly the root-cause message even when it contains `{}`. Add to `ErrorHandlerFactoryTest`:
```java
    @Test
    @DisplayName("a root-cause message containing '{}' is emitted verbatim (constant log template)")
    void rootCauseWithBracesIsEmittedVerbatim() {
        var msg = "bad value {} not allowed";
        var ex = new BotCommandException(context, new IllegalStateException(msg));
        StepVerifier.create(factory.handle(ex))
            .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo(msg))
            .verifyComplete();
    }
```
  This passes regardless of the log template (the text is independent of logging), but it pins behavior and documents the E intent; the real change is the log call shape.
- [ ] **GREEN:** use a constant SLF4J template with the detail as a parameter.

**Before:**
```java
        log.error(botMessage, exception);
```
**After:**
```java
        log.error("Command failed: {}", botMessage, exception);
```
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=ErrorHandlerFactoryTest`. Commit: `fix: use constant log template in BotCommandErrorHandler`.

### Task E2 — `BotAuthErrorHandler` ERROR→WARN, no stack trace, with context

**Files:** `src/main/java/com/kb/sessionbot/errors/handler/BotAuthErrorHandler.java`

- [ ] Lower the log level to WARN, drop the stack trace, and include chat id (an expected rejection, not an error).

**Before:**
```java
    @Override
    public Mono<? extends PartialBotApiMethod<?>> handle(BotAuthException exception) {
        log.error("Authentication failure", exception);
        return Mono.fromSupplier(() ->
```
**After:**
```java
    @Override
    public Mono<? extends PartialBotApiMethod<?>> handle(BotAuthException exception) {
        log.warn("Authentication rejected in chat {}: {}", exception.getContext().getChatId(), exception.getMessage());
        return Mono.fromSupplier(() ->
```
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=ErrorHandlerFactoryTest` (existing auth-routing test still green). Commit: `fix: log auth rejection at WARN without stack trace`.

### Task E3 — `UpdateWrapper` PII-trim "cannot get chat id"

**Files:** `src/main/java/com/kb/sessionbot/model/UpdateWrapper.java`

- [ ] Replace the full-`Update` ERROR log with update id + a short type descriptor (no PII payload).

**Before:**
```java
        log.error("Cannot get chat id from update.{}", update);
        throw new RuntimeException("Cannot get chat id from update");
```
**After:**
```java
        log.error("Cannot get chat id from update id={} (type={})", update.getUpdateId(), describeType(update));
        throw new RuntimeException("Cannot get chat id from update");
```
- [ ] Add a small private helper at the bottom of the class:
```java
    private static String describeType(Update update) {
        if (update.hasMessage()) return "message";
        if (update.hasCallbackQuery()) return "callbackQuery";
        if (update.hasEditedMessage()) return "editedMessage";
        if (update.hasInlineQuery()) return "inlineQuery";
        return "other";
    }
```
  (`Update` exposes these `has*()` predicates in telegrambots 10.0.0; if `hasEditedMessage`/`hasInlineQuery` are unavailable at compile time, drop those two lines — keep `message`/`callbackQuery`/`other`. Verify with `mvn clean test-compile`.)
- [ ] `mvn clean test-compile`. Commit: `fix: avoid logging full Update payload on missing chat id`.

### Task E4 — Correlation params on key dispatch/error lines

**Files:** `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- [ ] The dispatch line already logs `context.getCommand()`, `context.getChatId()`, `context.getState()` and `consume` already logs `updateId` — per the spec, add **nothing** to `consume`. The per-group error path executes `errorHandler.handle(error)`; add a `chatId`-bearing debug/log on the failure side effect so a routed error is correlatable. In `init()`'s `onErrorResume`:

**Before:**
```java
                    .flatMap(updates -> this.handleUpdates(updates.publishOn(Schedulers.boundedElastic()))
                        .onErrorResume(error -> errorHandler.handle(error).doOnNext(this::executeMessage)))
```
**After:**
```java
                    .flatMap(updates -> this.handleUpdates(updates.publishOn(Schedulers.boundedElastic()))
                        .onErrorResume(error -> {
                            log.warn("Handling pipeline error in a chat group", error);
                            return errorHandler.handle(error).doOnNext(this::executeMessage);
                        }))
```
  (The grouped flux key is the chatId, but it is not directly accessible here without capturing it; the per-handler logs from E1/E2 carry the chat id from the exception's context, so this line stays a generic correlatable WARN. This keeps E "lightweight, explicit params, no MDC/Reactor-context machinery" per the approved decision.)
- [ ] `mvn clean test-compile` then `mvn -q test -Dtest=CommandsSessionBotTest` (failure-isolation test still green). Commit: `fix: log pipeline errors at WARN in the per-chat error path`.

---

## Self-review checklist (run before declaring done)

- [ ] Every spec item has a task: A1–A5 (+A6 tests) ✓; B1–B9 ✓; E correlation/PII-trim/constant-template/throwables/levels ✓ (E folded into B3/B4/B2 + standalone E1–E4).
- [ ] No placeholders / "similar to" — every task shows complete before/after production code and complete test code.
- [ ] Naming consistency: `MAX_CALLBACK_BYTES` (CommandConstants + CommandBuilder), `NULL_ANSWER` (CommandConstants + CommandBuilder + CommandsDispatcher) used consistently.
- [ ] Type consistency: media `case` types match the verified `TelegramClient` overloads; all under `org.telegram.telegrambots.meta.api.methods.send`.
- [ ] B4 catch refinement precedes B8 (B8 relies on the `catch (BotCommandException)` branch).
- [ ] Every task gated by `mvn clean test-compile` under JDK 21; commits conventional, no AI attribution, no `.DS_Store`/`target/`/`.claude/` staged.