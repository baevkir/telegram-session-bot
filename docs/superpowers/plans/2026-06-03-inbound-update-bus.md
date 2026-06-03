# InboundUpdateBus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract inbound update intake into a swappable `InboundUpdateBus` SPI whose default groups updates per chat with `groupBy` + an idle `timeout`, and have `TelegramUpdateHandler` complete a chat's stream once its command closes — fixing `groupBy`'s unbounded-group / 256-slot footguns (the memory leak) without a cache.

**Architecture:** `consume()` calls `inboundUpdateBus.emit(update)`. The default `SinkInboundUpdateBus` exposes `Flux<ChatUpdateStream>` via `sink → map(wrap) → groupBy(chatId) → map(g → ChatUpdateStream(key, g.timeout(idleTtl, empty)))`. The coordinator `flatMap`s (bounded by `maxConcurrentChats`) each stream into `handleUpdates`, which collects each context's results and completes the stream after a command reaches `close` (eager release); the idle `timeout` is the safety net for abandoned conversations.

**Tech Stack:** Java 21, Maven, Spring Boot 3.5, Project Reactor (`groupBy`/`timeout`/`flatMap`/`collectList`/`takeUntil`/`concatMapIterable`), Lombok, JUnit 5, Mockito, AssertJ, `reactor-test` `StepVerifier`. No new third-party dependency.

Source of truth: `docs/superpowers/specs/2026-06-03-inbound-update-bus-design.md`.

---

## Environment & conventions

- **Build under Java 21.** Before every build run:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # ms-21.0.8 is installed
  ```
- **Per-task build gate:** `mvn clean test-compile`.
- **Test execution authorized for this run:** each task lists a focused `mvn test -Dtest=<Class>`
  command; run it (write-fail-fix-pass). Do **not** run the full `mvn test` suite.
- **One commit per task.** Conventional messages. **No AI / Claude attribution trailers.** Never
  stage `.DS_Store`, `.claude/`, `.idea/`, or `target/`.
- **Reuse existing fixtures** (`Fixtures`, `OrderCommand`, `EchoCommand`, `FixtureCommandConfig`).
- **Branch:** `inbound-update-bus` (off merged `master`).

## File structure

| File | Action | Task |
|------|--------|------|
| `src/main/java/com/kb/sessionbot/TelegramUpdateHandler.java` | Modify (complete on close) | 1 ✅ |
| `src/test/java/com/kb/sessionbot/TelegramUpdateHandlerTest.java` | Modify (eager-completion test) | 1 ✅ |
| `src/main/java/com/kb/sessionbot/ChatUpdateStream.java` | Create | 2 |
| `src/main/java/com/kb/sessionbot/InboundUpdateBus.java` | Create | 2 |
| `src/main/java/com/kb/sessionbot/SinkInboundUpdateBus.java` | Create | 3 |
| `src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java` | Create | 3 |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java` | Modify (2 properties) | 4 |
| `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` | Modify (consume + init + fields) | 5 |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java` | Modify (bean + wiring) | 5 |
| `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` | Modify (retarget factory) | 5 |

---

## Task 1: Complete a chat stream when its command closes — ✅ DONE (commit `d88f5fe`)

`TelegramUpdateHandler` was refactored to fold → dispatch (extracted `fold`/`dispatch` methods) →
collect each context's results into a `DispatchOutcome(context, results)` →
`takeUntil(close)` → `concatMapIterable(results)`. This completes the per-chat stream after a command
closes (drop-free, cleanup messages preserved). `completesAfterCommandClose` test added.

- [x] Handler refactored (`collectList` per context, complete after `close`).
- [x] `completesAfterCommandClose` test added.
- [x] `mvn test -Dtest=TelegramUpdateHandlerTest` → 10/10 pass.
- [x] Committed `d88f5fe`.

---

## Task 2: `ChatUpdateStream` holder + `InboundUpdateBus` SPI

**Files:** Create `ChatUpdateStream.java`, `InboundUpdateBus.java`. Pure new types — build stays green.

- [ ] **Step 1: Create `ChatUpdateStream.java`**

```java
package com.kb.sessionbot;

import com.kb.sessionbot.model.UpdateWrapper;
import reactor.core.publisher.Flux;

/** A single chat's ordered stream of updates, tagged with its chat id. */
public record ChatUpdateStream(String chatId, Flux<UpdateWrapper> updates) {}
```

- [ ] **Step 2: Create `InboundUpdateBus.java`**

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

- [ ] **Step 3: Build gate** — `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn clean test-compile` → BUILD SUCCESS.
- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/kb/sessionbot/ChatUpdateStream.java \
        src/main/java/com/kb/sessionbot/InboundUpdateBus.java
git commit -m "feat: add InboundUpdateBus SPI and ChatUpdateStream holder"
```

---

## Task 3: `SinkInboundUpdateBus` default implementation

**Files:** Create `SinkInboundUpdateBus.java`, `SinkInboundUpdateBusTest.java`.

- [ ] **Step 1: Write the failing tests** — create `src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java`:

```java
package com.kb.sessionbot;

import com.kb.sessionbot.fixtures.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SinkInboundUpdateBusTest {

    @DisplayName("emit announces one ChatUpdateStream tagged with the chat id")
    @Test
    void emitAnnouncesChatStream() {
        var bus = new SinkInboundUpdateBus(Duration.ofMinutes(30));

        StepVerifier.create(bus.updates())
            .then(() -> bus.emit(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")))
            .assertNext(stream -> assertThat(stream.chatId()).isEqualTo(String.valueOf(Fixtures.CHAT_ID)))
            .thenCancel()
            .verify();
    }

    @DisplayName("distinct chats produce distinct streams, in arrival order")
    @Test
    void distinctChatsProduceDistinctStreams() {
        var bus = new SinkInboundUpdateBus(Duration.ofMinutes(30));

        StepVerifier.create(bus.updates().map(ChatUpdateStream::chatId))
            .then(() -> bus.emit(Fixtures.messageUpdate(1, 100L, 1, "/order?buy")))
            .then(() -> bus.emit(Fixtures.messageUpdate(2, 200L, 2, "/order?buy")))
            .expectNext("100", "200")
            .thenCancel()
            .verify();
    }

    @DisplayName("a chat's stream completes after the idle TTL with no updates")
    @Test
    void idleStreamCompletesAfterTtl() {
        var holder = new SinkInboundUpdateBus[1];
        StepVerifier.withVirtualTime(() -> {
                holder[0] = new SinkInboundUpdateBus(Duration.ofMinutes(30));
                return holder[0].updates().flatMap(ChatUpdateStream::updates);
            })
            .then(() -> holder[0].emit(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")))
            .expectNextCount(1)
            .thenAwait(Duration.ofMinutes(31))
            .verifyComplete();
    }

    @DisplayName("a new update after idle completion recreates a fresh stream for the same chat")
    @Test
    void streamRecreatedAfterIdleCompletion() {
        var holder = new SinkInboundUpdateBus[1];
        var chatIds = new CopyOnWriteArrayList<String>();
        StepVerifier.withVirtualTime(() -> {
                holder[0] = new SinkInboundUpdateBus(Duration.ofMinutes(30));
                return holder[0].updates()
                    .flatMap(stream -> { chatIds.add(stream.chatId()); return stream.updates(); });
            })
            .then(() -> holder[0].emit(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")))
            .expectNextCount(1)
            .thenAwait(Duration.ofMinutes(31))
            .then(() -> holder[0].emit(Fixtures.messageUpdate(2, Fixtures.CHAT_ID, 101, "/order?buy")))
            .expectNextCount(1)
            .thenCancel()
            .verify();

        assertThat(chatIds)
            .containsExactly(String.valueOf(Fixtures.CHAT_ID), String.valueOf(Fixtures.CHAT_ID));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail** — `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn test -Dtest=SinkInboundUpdateBusTest` → FAIL (class missing).
- [ ] **Step 3: Create `SinkInboundUpdateBus.java`**

```java
package com.kb.sessionbot;

import com.kb.sessionbot.model.UpdateWrapper;
import org.telegram.telegrambots.meta.api.objects.Update;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Default in-process {@link InboundUpdateBus}. Updates are buffered in a unicast Reactor sink,
 * grouped per chat with {@code groupBy}, and each chat's stream is completed after {@code idleTtl}
 * of inactivity via {@code timeout} (the safety net for abandoned conversations; completed commands
 * are released earlier by the handler). The single producer is the long-polling thread, so
 * {@code emit} uses {@code FAIL_FAST}.
 */
public class SinkInboundUpdateBus implements InboundUpdateBus {

    private final Sinks.Many<Update> sink = Sinks.many().unicast().onBackpressureBuffer();
    private final Duration idleTtl;

    public SinkInboundUpdateBus(Duration idleTtl) {
        this.idleTtl = idleTtl;
    }

    @Override
    public void emit(Update update) {
        sink.emitNext(update, Sinks.EmitFailureHandler.FAIL_FAST);
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

- [ ] **Step 4: Run the tests to verify they pass** — `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn test -Dtest=SinkInboundUpdateBusTest` → all four PASS.
- [ ] **Step 5: Build gate** — `mvn clean test-compile` → BUILD SUCCESS.
- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kb/sessionbot/SinkInboundUpdateBus.java \
        src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java
git commit -m "feat: add SinkInboundUpdateBus default with groupBy + idle timeout"
```

---

## Task 4: Configuration properties

**Files:** Modify `CommandsSessionBotProperties.java`.

- [ ] **Step 1: Add the two properties** — after:

```java
package com.kb.sessionbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "sessionbot.telegram")
public class CommandsSessionBotProperties {
    private String token;
    private String botUsername;
    /** Idle period after which an inactive chat's update stream is released. */
    private Duration chatIdleTtl = Duration.ofMinutes(30);
    /** Maximum number of chats processed concurrently (per-chat fan-out concurrency). */
    private int maxConcurrentChats = 256;
}
```

- [ ] **Step 2: Build gate** — `mvn clean test-compile` → BUILD SUCCESS.
- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java
git commit -m "feat: add chat-idle-ttl and max-concurrent-chats properties"
```

---

## Task 5: Wire the bus into the coordinator and auto-configuration

**Files:** Modify `CommandsSessionBot.java` (whole file), `CommandsSessionBotConfiguration.java`
(bean + `bot`), `CommandsSessionBotTest.java` (factory only). Atomic — the constructor change forces
all three together.

- [ ] **Step 1: Replace `CommandsSessionBot.java` entirely**

```java
package com.kb.sessionbot;

import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Reactive long-polling coordinator. Incoming updates are pushed to an {@link InboundUpdateBus},
 * whose per-chat {@link ChatUpdateStream}s are dispatched through a {@link TelegramUpdateHandler};
 * out-of-band messages from the {@link OutboundMessageBus} and the startup command list are executed
 * through a {@link MessageExecutor}. Three independent subscriptions are disposed on shutdown.
 */
@Slf4j
public class CommandsSessionBot implements LongPollingSingleThreadUpdateConsumer {

    private final CommandsFactory commandsFactory;
    private final ErrorHandlerFactory errorHandler;
    private final MessageExecutor messageExecutor;
    private final OutboundMessageBus outboundMessageBus;
    private final TelegramUpdateHandler updateHandler;
    private final InboundUpdateBus inboundUpdateBus;
    private final int maxConcurrentChats;
    private final Disposable.Composite subscriptions = Disposables.composite();

    public CommandsSessionBot(
        CommandsFactory commandsFactory,
        ErrorHandlerFactory errorHandler,
        MessageExecutor messageExecutor,
        OutboundMessageBus outboundMessageBus,
        TelegramUpdateHandler updateHandler,
        InboundUpdateBus inboundUpdateBus,
        int maxConcurrentChats
    ) {
        this.commandsFactory = commandsFactory;
        this.errorHandler = errorHandler;
        this.messageExecutor = messageExecutor;
        this.outboundMessageBus = outboundMessageBus;
        this.updateHandler = updateHandler;
        this.inboundUpdateBus = inboundUpdateBus;
        this.maxConcurrentChats = maxConcurrentChats;
    }

    @Override
    public void consume(Update update) {
        log.debug("Received update id={}", update.getUpdateId());
        inboundUpdateBus.emit(update);
    }

    @PostConstruct
    public void init() {
        var setMyCommands = Flux.fromIterable(commandsFactory.getCommands())
            .filter(command -> !command.hidden())
            .map(command -> BotCommand.builder().command(command.getCommandIdentifier()).description(command.getDescription()).build())
            .collectList()
            .map(commands -> SetMyCommands.builder().commands(commands).build())
            .subscribe(messageExecutor::execute, error -> log.error("Bot pipeline terminated unexpectedly", error));

        subscriptions.add(setMyCommands);

        subscriptions.add(
            inboundUpdateBus.updates()
                .flatMap(this::handleChat, maxConcurrentChats)
                .subscribe(
                    ignored -> { },
                    error -> log.error("Bot pipeline terminated unexpectedly", error)));

        subscriptions.add(
            outboundMessageBus.messages()
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                    messageExecutor::execute,
                    error -> log.error("Bot pipeline terminated unexpectedly", error)));
    }

    private Flux<PartialBotApiMethod<?>> handleChat(ChatUpdateStream stream) {
        return updateHandler.handleUpdates(stream.updates().publishOn(Schedulers.boundedElastic()))
            .onErrorResume(error -> {
                log.warn("Handling pipeline error in chat {}", stream.chatId(), error);
                return errorHandler.handle(error).doOnNext(messageExecutor::execute);
            });
    }

    @PreDestroy
    public void shutdown() {
        if (!subscriptions.isDisposed()) {
            subscriptions.dispose();
        }
    }
}
```

(Removed vs current: the `Sinks.Many<Update> updatesSink` field, the `consume` `emitNext`, the
`map(UpdateWrapper::wrap).groupBy(...)` in `init`, and the now-unused `UpdateWrapper` and `Sinks`
imports. Added the `PartialBotApiMethod` import for `handleChat`'s return type.)

- [ ] **Step 2: Update the auto-configuration** — in `CommandsSessionBotConfiguration.java`, add
  `import com.kb.sessionbot.InboundUpdateBus;` and `import com.kb.sessionbot.SinkInboundUpdateBus;`
  (verify the block after editing), then replace the `bot` bean. Before:

```java
    @Bean
    public CommandsSessionBot bot(
            CommandsFactory commandsFactory,
            ErrorHandlerFactory errorHandler,
            MessageExecutor messageExecutor,
            OutboundMessageBus outboundMessageBus,
            TelegramUpdateHandler telegramUpdateHandler) {
        return new CommandsSessionBot(commandsFactory, errorHandler, messageExecutor, outboundMessageBus, telegramUpdateHandler);
    }
```

After:

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

- [ ] **Step 3: Update the test factory** — in `CommandsSessionBotTest.java`, add
  `import java.time.Duration;` and replace the `bot(...)` factory. After:

```java
    private CommandsSessionBot bot(AuthInterceptor auth) {
        var executor = new TelegramClientMessageExecutor(telegramClient, errorHandlerFactory);
        var updateHandler = new TelegramUpdateHandler(commandsFactory, auth, executor);
        var inboundUpdateBus = new SinkInboundUpdateBus(Duration.ofMinutes(30));
        return new CommandsSessionBot(
            commandsFactory, errorHandlerFactory, executor, outboundMessageBus, updateHandler,
            inboundUpdateBus, 256);
    }
```

The existing test methods (`consumeEndToEnd`, `failureInOneChatDoesNotKillPipeline`,
`outboundMessagesExecuteThroughCoordinator`, `shutdownDisposesSubscriptions`) are unchanged — they
drive through `consume`/`init`, which now route through the bus but preserve observable behavior.

- [ ] **Step 4: Build gate** — `mvn clean test-compile` → BUILD SUCCESS.
- [ ] **Step 5: Run the focused suite** — `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn test -Dtest=CommandsSessionBotTest,SinkInboundUpdateBusTest,TelegramUpdateHandlerTest` → all PASS.
- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kb/sessionbot/CommandsSessionBot.java \
        src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java \
        src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java
git commit -m "feat: route updates through InboundUpdateBus with bounded per-chat dispatch"
```

---

## Owner acceptance

After all tasks: `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn test` (full sweep) and finish
the branch (merge / PR) via the finishing-a-development-branch flow.

## Self-review against the spec

- **Handler completes on close (collectList → takeUntil)** — Task 1 (done, `d88f5fe`).
- **`InboundUpdateBus` SPI + `ChatUpdateStream`** — Task 2.
- **`SinkInboundUpdateBus` (`groupBy` + `timeout`, `FAIL_FAST`)** — Task 3, with announce / distinct /
  idle-completion (virtual time) / recreation tests.
- **`chat-idle-ttl` (30m) + `max-concurrent-chats` (256)** — Task 4.
- **Coordinator `consume → emit`, `flatMap(maxConcurrentChats)`, `onErrorResume` logging `chatId`,
  `updatesSink` removed; `@ConditionalOnMissingBean InboundUpdateBus` + `bot` wiring** — Task 5.
- **No new dependency** — confirmed.
- **Type/name consistency:** `InboundUpdateBus.emit/updates`, `ChatUpdateStream(chatId, updates)`,
  `SinkInboundUpdateBus(Duration)`, `getChatIdleTtl()`/`getMaxConcurrentChats()` across tasks.
- **Accepted limitation (teardown drop-race)** — documented in the spec.
