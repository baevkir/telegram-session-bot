# InboundUpdateBus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract inbound update intake into a swappable `InboundUpdateBus` SPI whose default groups updates per chat with `groupBy` + an idle `timeout`, fixing `groupBy`'s unbounded-group / 256-slot footguns without a cache and without touching `TelegramUpdateHandler`.

**Architecture:** `consume()` calls `inboundUpdateBus.emit(update)`. The default `SinkInboundUpdateBus` exposes `Flux<ChatUpdateStream>` via `sink → map(wrap) → groupBy(chatId) → map(g → ChatUpdateStream(key, g.timeout(idleTtl, empty)))`. The coordinator `flatMap`s (bounded by `maxConcurrentChats`) each stream into the existing `handleUpdates`. Idle chats are released by the per-group `timeout`.

**Tech Stack:** Java 21, Maven, Spring Boot 3.5, Project Reactor (`groupBy`/`timeout`/`flatMap`), Lombok, JUnit 5, Mockito, AssertJ, `reactor-test` `StepVerifier`. No new third-party dependency.

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
- **Branch:** `inbound-update-bus` (already created off merged `master`).
- **`TelegramUpdateHandler` is NOT modified by this plan.**

## File structure

| File | Action | Task |
|------|--------|------|
| `src/main/java/com/kb/sessionbot/ChatUpdateStream.java` | Create | 1 |
| `src/main/java/com/kb/sessionbot/InboundUpdateBus.java` | Create | 1 |
| `src/main/java/com/kb/sessionbot/SinkInboundUpdateBus.java` | Create | 2 |
| `src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java` | Create | 2 |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java` | Modify (2 properties) | 3 |
| `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` | Modify (consume + init + fields) | 4 |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java` | Modify (bean + wiring) | 4 |
| `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` | Modify (retarget factory) | 4 |

---

## Task 1: `ChatUpdateStream` holder + `InboundUpdateBus` SPI

**Files:**
- Create: `src/main/java/com/kb/sessionbot/ChatUpdateStream.java`
- Create: `src/main/java/com/kb/sessionbot/InboundUpdateBus.java`

Pure new types, no wiring yet — build stays green.

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

- [ ] **Step 3: Build gate**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn clean test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/kb/sessionbot/ChatUpdateStream.java \
        src/main/java/com/kb/sessionbot/InboundUpdateBus.java
git commit -m "feat: add InboundUpdateBus SPI and ChatUpdateStream holder"
```

---

## Task 2: `SinkInboundUpdateBus` default implementation

**Files:**
- Create: `src/main/java/com/kb/sessionbot/SinkInboundUpdateBus.java`
- Test: `src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java`:

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
            .expectNextCount(1)                       // the update flows through the inner stream
            .thenAwait(Duration.ofMinutes(31))        // idle past the TTL
            .verifyComplete();                        // timeout completes the inner stream
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
            .thenAwait(Duration.ofMinutes(31))        // first stream idle-evicted
            .then(() -> holder[0].emit(Fixtures.messageUpdate(2, Fixtures.CHAT_ID, 101, "/order?buy")))
            .expectNextCount(1)                       // second update flows through the recreated stream
            .thenCancel()
            .verify();

        assertThat(chatIds)
            .containsExactly(String.valueOf(Fixtures.CHAT_ID), String.valueOf(Fixtures.CHAT_ID));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn test -Dtest=SinkInboundUpdateBusTest`
Expected: FAIL to compile — `SinkInboundUpdateBus` does not exist yet.

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
 * of inactivity via {@code timeout} (so {@code groupBy} evicts the idle group and downstream
 * fan-out slots are freed). The single producer is the long-polling thread, so {@code emit} uses
 * {@code FAIL_FAST}.
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

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn test -Dtest=SinkInboundUpdateBusTest`
Expected: PASS — all four cases green.

- [ ] **Step 5: Build gate**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn clean test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kb/sessionbot/SinkInboundUpdateBus.java \
        src/test/java/com/kb/sessionbot/SinkInboundUpdateBusTest.java
git commit -m "feat: add SinkInboundUpdateBus default with groupBy + idle timeout"
```

---

## Task 3: Configuration properties

**Files:**
- Modify: `src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java`

- [ ] **Step 1: Add the two properties**

Before:

```java
package com.kb.sessionbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "sessionbot.telegram")
public class CommandsSessionBotProperties {
    private String token;
    private String botUsername;
}
```

After:

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

- [ ] **Step 2: Build gate**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn clean test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java
git commit -m "feat: add chat-idle-ttl and max-concurrent-chats properties"
```

---

## Task 4: Wire the bus into the coordinator and auto-configuration

**Files:**
- Modify: `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` (whole file)
- Modify: `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java` (bean + `bot` wiring)
- Modify: `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` (factory only)

The coordinator constructor changes, so the config bean and the test factory must change in the
same commit to stay green.

- [ ] **Step 1: Replace `CommandsSessionBot.java` entirely**

```java
package com.kb.sessionbot;

import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
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
 * out-of-band messages from the {@link OutboundMessageBus} and the startup command list are
 * executed through a {@link MessageExecutor}. Three independent subscriptions are disposed on
 * shutdown.
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
                .flatMap(stream -> updateHandler.handleUpdates(stream.updates().publishOn(Schedulers.boundedElastic()))
                    .onErrorResume(error -> {
                        log.warn("Handling pipeline error in chat {}", stream.chatId(), error);
                        return errorHandler.handle(error).doOnNext(messageExecutor::execute);
                    }),
                    maxConcurrentChats)
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

    @PreDestroy
    public void shutdown() {
        if (!subscriptions.isDisposed()) {
            subscriptions.dispose();
        }
    }
}
```

(Removed vs the current version: the `Sinks.Many<Update> updatesSink` field, the `consume`
`emitNext`, the `map(UpdateWrapper::wrap).groupBy(...)` in `init`, and the now-unused `UpdateWrapper`
and `Sinks` imports.)

- [ ] **Step 2: Update the auto-configuration**

In `CommandsSessionBotConfiguration.java`, add the `inboundUpdateBus` bean and repoint the `bot`
bean. Add `import com.kb.sessionbot.InboundUpdateBus;` and `import com.kb.sessionbot.SinkInboundUpdateBus;`
(verify the import block after editing). Before (the `bot` bean):

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

- [ ] **Step 3: Update the test factory**

In `CommandsSessionBotTest.java`, add `import java.time.Duration;` and replace the `bot(...)`
factory. Before:

```java
    private CommandsSessionBot bot(AuthInterceptor auth) {
        var executor = new TelegramClientMessageExecutor(telegramClient, errorHandlerFactory);
        var updateHandler = new TelegramUpdateHandler(commandsFactory, auth, executor);
        return new CommandsSessionBot(
            commandsFactory, errorHandlerFactory, executor, outboundMessageBus, updateHandler);
    }
```

After:

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

The existing test methods are unchanged: `consumeEndToEnd`, `failureInOneChatDoesNotKillPipeline`,
`outboundMessagesExecuteThroughCoordinator`, and `shutdownDisposesSubscriptions` all drive through
`consume`/`init`, which now route through the bus but preserve the same observable behavior.

- [ ] **Step 4: Build gate**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn clean test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run the focused suite to verify behavior**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21); mvn test -Dtest=CommandsSessionBotTest,SinkInboundUpdateBusTest`
Expected: PASS — coordinator end-to-end, failure isolation, outbound, shutdown, and the bus cases all green.

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

- **`InboundUpdateBus` SPI + `ChatUpdateStream`** — Task 1.
- **`SinkInboundUpdateBus` (`groupBy` + `timeout`, `FAIL_FAST` emit)** — Task 2, with tests for
  announce, distinct chats, idle completion (virtual time), and recreation.
- **`chat-idle-ttl` (30m) + `max-concurrent-chats` (256)** — Task 3.
- **Coordinator `consume → emit`, `flatMap(maxConcurrentChats)`, per-chat `onErrorResume` logging
  `chatId`, `updatesSink` removed** — Task 4.
- **Auto-config `@ConditionalOnMissingBean InboundUpdateBus` + `bot` wiring** — Task 4.
- **No new dependency; `TelegramUpdateHandler` unchanged** — confirmed.
- **Type/name consistency:** `InboundUpdateBus.emit/updates`, `ChatUpdateStream(chatId, updates)`,
  `SinkInboundUpdateBus(Duration)`, `getChatIdleTtl()`/`getMaxConcurrentChats()` (Lombok accessors)
  used consistently across Tasks 1–4.
- **Accepted limitation (teardown drop-race)** — inherent to `groupBy`; documented in the spec.
