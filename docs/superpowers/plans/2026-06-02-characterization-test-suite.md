# Characterization Test Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an all-green characterization/unit test suite that locks the *current intended behavior* of the wire format, command dispatch, parameter binding, method matching, session-context lifecycle, update adaptation, error routing, and the per-chat reactive fold — so the later concurrency rework (Spec 2) and design refactor (Spec 4) can be verified to preserve behavior. The 9 group-B bug behaviors and concurrency-guarantee tests are explicitly **excluded** (they land in Spec 2 red→green).

**Architecture:** Spring Boot 3.5 auto-configuration starter (`com.kb:telegram-session-bot`), Java 21, Maven, Project Reactor, Lombok, `org.telegram:telegrambots*` 10.0.0. The reactive bot folds per-chat update streams into a `CommandContext` via `scanWith`; `CommandsDispatcher` reflects over `@BotCommand`/`@CommandMethod` beans, scores methods with `MethodMatcher`, binds parameters (Jackson + auto-injection), and either invokes the match or renders a prompt. Tests mirror the main package structure under `src/test/java`, use real reflection through fixture `@BotCommand` classes (not mocks) for dispatch/flow tests, and drive the reactive fold deterministically with `StepVerifier`.

**Tech Stack:** JUnit 5 (Jupiter), AssertJ `3.27.7`, Mockito `5.17.0` (all already on the test classpath via `spring-boot-starter-test`), plus `io.projectreactor:reactor-test` (added in Task 0, test scope, version managed by the SB 3.5 BOM). `@Nested` + `@DisplayName` grouping; `@ParameterizedTest`/`@CsvSource`/`@MethodSource` for wire-format and scoring permutations; `assertThatThrownBy` for guard cases; `StepVerifier` for reactive assertions.

---

## Confirmed facts from source (do not re-derive)

- `CommandConstants`: `COMMAND_START="/"`, `COMMAND_PARAMETERS_SEPARATOR="?"`, `COMMAND_PARAMETERS_SEPARATOR_REGEX="\\?"`, `PARAMETER_SEPARATOR="&"`, `KEY_VALUE_SEPARATOR=":"`, `DYNAMIC_PARAMETERS_SEPARATOR="#"`. Dynamic param keys: `refreshContext`, `scipAnswer`, `approved`, `initiator`.
- `CommandBuilder.build()`: emits `/`+command, then `?` only if command **and** answers both present, then `&`-joined answers, then `#`+`&`-joined `key[:value]` params. A param with `null` value emits just the key (no `:`). Warns (does not throw) when `> 64` bytes. Answers preserve insertion order; **params come from a `HashMap`** so multi-param ordering is not deterministic — tests must not assert a fixed order across two+ params (assert on the parsed round-trip instead).
- `MessageDescriptor.parse(text)`: `Assert.isTrue(StringUtils.hasText(text), "text is empty")` → throws `IllegalArgumentException` on null/blank. `isCommand()` true iff text starts with `/`. Answers for non-command text are split off everything before the first `#`. A bare `#k:v` (no leading `/`, no answers) → empty answers, one param. A dynamic param written as bare key (`#refreshContext`) parses to value `""`.
- `MessageDescriptor.parseAnswers` edge: `"/cmd?"` → `commandSplit = ["/cmd"]` (trailing empty dropped by `split`), length 1 → **empty answers**. `"/cmd?#k:v"` → `commandSplit=["/cmd","#k:v"]`, paramsSplit on `#` → `[""]`, then `"".split("&")` → `[""]` → answers = `[""]` (one empty string). `"a&b"` (no `/`) → answers `["a","b"]`.
- `DynamicParameters.canScipAnswer(index)`: false if no `scipAnswer` key; else `Integer.parseInt(value) >= index`. `needRefreshContext`/`commandApproved` are key-presence checks; `getInitiator` returns the raw value (or null).
- `UpdateWrapper.wrap(update)`: NPE-guards null update; text comes from message text or callback data; `MessageDescriptor.parse("")` would throw, but `getText` returns `Optional` defaulted to `""` — **so `wrap` of an update with neither message nor callback throws `IllegalArgumentException` ("text is empty")**. `getChatId` reads message chat id or callback message chat id, else throws `RuntimeException("Cannot get chat id from update")`. `isCommand()` = `(hasMessage && message.isCommand()) || descriptor.isCommand()`. `getFrom` = message.from else callback.from else null.
- `CommandContext`: `create` requires `update.isCommand()` (else `IllegalArgumentException`). `addUpdate` rejects commands (else `IllegalArgumentException`). `getAnswers()` = stored `answers` (from command update) ++ `getPendingArguments()` (current/last update's answers), unmodifiable. `getChatId()` uses commandUpdate else currentUpdate, null if both absent. `empty()` has null commandUpdate → `isEmpty()` true. `getDynamicParams()` reads current update's params, falling back to commandUpdate's.
- `MethodMatcher.getMatchingScore(template,args)`: placeholder segment → `score--`; literal segment with `args.size() < index+1` → `Integer.MIN_VALUE`; literal mismatch → `Integer.MIN_VALUE`; literal match → `score += 10`. `getMatchingMethod`: if a default (`""` arguments) method exists and answers empty → return it; else filter `args.size() <= template.size()`, score, drop `MIN_VALUE`, pick max. Methods keyed by `arguments` string in a map (duplicate templates collapse — test only single-match paths; the duplicate-template fix is Spec 2).
- `CommandsDispatcher.invoke`: binds `@Parameter` args via `mapper.convertValue(answer, type)` (Jackson + JavaTimeModule); the literal string `"null"` binds to `null`. Optional+skippable (`canScipAnswer(index)`) → binds `null`; missing required → sets `invocationArgument` (renderer prompt) and returns. Auto-injection by type+name: `UpdateWrapper command`, `UpdateWrapper update`, `Update update`, `User from`, `String chatId`, `DynamicParameters` (any name), `CommandContext` (any name). No method match → default renderer prompt via `getDefaultRenderer()` (`applicationContext.getBean("defaultParameterRenderer", ...)`), returns null method. `-parameters` is ON (SB parent default) so reflective parameter names are real.
- `DispatcherBotCommand.process`: asserts state != close; on `invocationArgument != null` → `startProgress()` and return the prompt publisher; else `close()` and return invocation results concatenated with `DeleteMessage`s for question messages + update message ids + callback message ids (distinct). Pending args are appended to context answers (or `""` if empty and `canScipAnswer(0)`).
- `CommandsSessionBot.handleUpdates(Flux<UpdateWrapper>)` is `private` today (Task 0 widens to package-private). It `scanWith(CommandContext::empty, ...)`, `skip(1)`, then per context: empty → `helpCommand.process` (also side-effect executes via `executeMessage`); else auth-gate (`false` → `BotAuthException`), then `commandsFactory.getCommand(...).process(context)`, and on each emitted message in `progress` state executes it and, if a `Message` comes back, calls `addQuestionMessage`. `executeMessage` only executes `BotApiMethod` instances (e.g. `SendMessage`, `DeleteMessage`); other `PartialBotApiMethod` throws `UnsupportedOperationException` (caught path is `TelegramApiException` only — so non-BotApiMethod propagates).
- DTO construction (telegrambots 10.0.0): `Message.builder()...build()` (SuperBuilder); `Message.getChatId()` derives from `chat.getId()` so set `.chat(Chat.builder().id(..).build())`. `Message.isCommand()` depends on entities and is impractical to force true — make updates "commands" via **text starting with `/`** (the descriptor branch). `Update` has no builder in a convenient form here; construct with `new Update()` + setters (`setUpdateId`, `setMessage`, `setCallbackQuery`). `CallbackQuery` via `new CallbackQuery()` + `setData`/`setMessage`/`setFrom`; its message type is `MaybeInaccessibleMessage` (a `Message` is one). `SendMessage`/`DeleteMessage` are `BotApiMethod`; both expose `getChatId()` (String), `SendMessage.getText()`/`getReplyMarkup()`, `DeleteMessage.getMessageId()` (Integer).

## Decisions / assumptions baked into this plan

- **Spec drift on renderer names:** the spec references `RenderingMethod`, `RenderingOption` annotation as `Option`, `CompositeParameterRenderer`, and a per-command child renderer factory (`createChild`). The actual code has **no** `@RenderingMethod`, no `CompositeParameterRenderer`, no `createChild`; the default renderer bean is `ParameterRendererFactory` (registered under bean name `defaultParameterRenderer`) and rendering options use `@RenderingOption`/`Option`. The plan tests **what exists**: `ParameterRendererFactory` selection, `TextParameterRenderer`, `BooleanParameterRenderer`, `DateParameterRenderer`, and `@Parameter(rendering=@Rendering(...))` driven selection through the real `ApplicationContext`. No `@RenderingMethod`/child-factory tests are written.
- **Command-detection via text prefix:** because `Message.isCommand()` is entity-driven and not builder-settable, fixtures and flow tests mark command updates by message text / callback data starting with `/`. This exercises the same `UpdateWrapper.isCommand()` OR-branch the production code relies on for callback-originated commands.
- **HashMap param ordering:** `CommandBuilder` stores params in a `HashMap`; tests assert multi-param output only via `MessageDescriptor.parse(build(...))` round-trip equality of the parsed map, never via a fixed substring with two params in a guessed order.
- **`consume()` integration timing:** `init()` wires the reactive pipeline in `@PostConstruct` and subscribes on the calling thread's sink; the unicast sinks deliver synchronously enough for a single fed update, but the test uses Awaitility-free polling via Mockito `verify(...).timeout(...)` (Mockito 5 `verify(mock, timeout(2000))`) to avoid flakiness. No new dependency needed.
- **`reactor-test` version:** managed by the SB 3.5 BOM — declared without an explicit `<version>`.
- **Group-B exclusions honored:** no assertions on per-chat ordering under contention, `sendMessage` thread-safety, duplicate-template resolution, or any known-buggy fold path. `MethodMatcherTest` tests only single-match templates.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `pom.xml` (modify) | Add `io.projectreactor:reactor-test` (test scope, BOM-managed). |
| `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` (modify) | Widen `handleUpdates` from `private` to package-private (testability seam). |
| `src/test/java/com/kb/sessionbot/fixtures/Fixtures.java` (create) | Static factory helpers to build `Update`/`Message`/`CallbackQuery`/`UpdateWrapper`/`CommandContext` from wire strings. |
| `src/test/java/com/kb/sessionbot/fixtures/OrderCommand.java` (create) | `@BotCommand("order")` fixture: literal+placeholder templates, required & optional params, Jackson-bound types, returns `BotApiMethod`. |
| `src/test/java/com/kb/sessionbot/fixtures/EchoCommand.java` (create) | `@BotCommand("echo")` fixture: default (no-args) method + every auto-injection parameter type. |
| `src/test/java/com/kb/sessionbot/fixtures/FixtureCommandConfig.java` (create) | `@Configuration` registering the fixture commands + the renderer/dispatch beans so `CommandsDispatcherTest` runs against a real `ApplicationContext`. |
| `src/test/java/com/kb/sessionbot/commands/CommandBuilderTest.java` (create) | `build()` output + round-trip with `MessageDescriptor` + 64-byte boundary. |
| `src/test/java/com/kb/sessionbot/commands/MessageDescriptorTest.java` (rewrite) | Parameterized parse cases + edges (blank throw, empty-value param, multi params, answers-only, trailing separators). |
| `src/test/java/com/kb/sessionbot/model/DynamicParametersTest.java` (create) | `canScipAnswer` boundary, `needRefreshContext`, `commandApproved`, `getInitiator`, `empty()`. |
| `src/test/java/com/kb/sessionbot/model/CommandContextTest.java` (create) | `create`/`addUpdate` guards, `getAnswers` merge, lifecycle transitions, refresh rebuild, `getChatId` null. |
| `src/test/java/com/kb/sessionbot/model/UpdateWrapperTest.java` (create) | `wrap` message vs callback, `getChatId` both branches + throw, `isCommand` OR-logic, `getFrom`, `getCallbackMessage`. |
| `src/test/java/com/kb/sessionbot/commands/dispatcher/MethodMatcherTest.java` (create) | `getMatchingScore` cases, highest-score-wins, default-when-empty, size filter, single-match only. |
| `src/test/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcherTest.java` (create) | `invoke` through fixtures + real context: Jackson binding, scip skip, missing→prompt, every auto-injection, no-match→default prompt. |
| `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java` (create) | `StepVerifier` over package-private `handleUpdates` + one `consume()` end-to-end with mocked `TelegramClient`. |
| `src/test/java/com/kb/sessionbot/errors/handler/ErrorHandlerFactoryTest.java` (create) | Cause-chain routing: exact-type handler selection, reverse (root-outward) walk, no-handler → empty. |
| `src/test/java/com/kb/sessionbot/config/CommandsSessionBotAutoConfigurationTest.java` (modify) | Broaden default-bean assertions + add one more override. |

---

## Task 0 — Add `reactor-test`; widen `handleUpdates` to package-private

**Files**
- Modify: `pom.xml`
- Modify: `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

**Steps**
- [ ] In `pom.xml`, inside `<dependencies>`, after the `spring-boot-starter-test` dependency, add:
```xml
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
```
- [ ] In `CommandsSessionBot.java`, change the method signature `private Flux<PartialBotApiMethod<?>> handleUpdates(Flux<UpdateWrapper> updates) {` to `Flux<PartialBotApiMethod<?>> handleUpdates(Flux<UpdateWrapper> updates) {` (drop `private`; leave the body and the `init()` call site untouched).
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS (compiles against the existing tests too).
- [ ] **Acceptance (owner-run):** `mvn test` green.
- [ ] Commit: `build: add reactor-test and expose handleUpdates for testing`.

---

## Task 1 — Test fixtures

**Files**
- Create: `src/test/java/com/kb/sessionbot/fixtures/Fixtures.java`
- Create: `src/test/java/com/kb/sessionbot/fixtures/OrderCommand.java`
- Create: `src/test/java/com/kb/sessionbot/fixtures/EchoCommand.java`
- Create: `src/test/java/com/kb/sessionbot/fixtures/FixtureCommandConfig.java`

**Steps**
- [ ] Create `Fixtures.java` (wire-string → DTO/wrapper/context helpers):
```java
package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.UpdateWrapper;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/** Builds Telegram DTOs and session objects from wire strings for tests. */
public final class Fixtures {

    public static final long CHAT_ID = 4242L;

    private Fixtures() {
    }

    public static User user(String username) {
        var user = new User();
        user.setId(7L);
        user.setUserName(username);
        user.setFirstName("Test");
        user.setIsBot(false);
        return user;
    }

    public static Message message(long chatId, int messageId, String text) {
        return Message.builder()
            .messageId(messageId)
            .chat(Chat.builder().id(chatId).type("private").build())
            .from(user("tester"))
            .text(text)
            .build();
    }

    /** A message-based update; command-ness is driven by the leading '/' in text. */
    public static Update messageUpdate(int updateId, long chatId, int messageId, String text) {
        var update = new Update();
        update.setUpdateId(updateId);
        update.setMessage(message(chatId, messageId, text));
        return update;
    }

    /** A callback-query update carrying wire data; its message is the question message. */
    public static Update callbackUpdate(int updateId, long chatId, int questionMessageId, String data) {
        var callback = new CallbackQuery();
        callback.setId("cb-" + updateId);
        callback.setData(data);
        callback.setFrom(user("tester"));
        callback.setMessage(message(chatId, questionMessageId, "question"));
        var update = new Update();
        update.setUpdateId(updateId);
        update.setCallbackQuery(callback);
        return update;
    }

    public static UpdateWrapper wrap(Update update) {
        return UpdateWrapper.wrap(update);
    }

    public static UpdateWrapper commandWrapper(String wire) {
        return wrap(messageUpdate(1, CHAT_ID, 100, wire));
    }

    public static UpdateWrapper answerWrapper(int updateId, int questionMessageId, String wire) {
        return wrap(callbackUpdate(updateId, CHAT_ID, questionMessageId, wire));
    }

    public static CommandContext contextFor(String commandWire) {
        return CommandContext.create(commandWrapper(commandWire));
    }
}
```
- [ ] Create `OrderCommand.java` (literal+placeholder templates, required & optional params, Jackson types, returns a `BotApiMethod`):
```java
package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.commands.dispatcher.annotations.BotCommand;
import com.kb.sessionbot.commands.dispatcher.annotations.CommandMethod;
import com.kb.sessionbot.commands.dispatcher.annotations.Parameter;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

@BotCommand(value = "order", description = "Order fixture", hidden = false)
public class OrderCommand {

    @CommandMethod(arguments = "buy&{product}")
    public Mono<SendMessage> buy(@Parameter("product") String product) {
        return Mono.just(SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("buy:" + product).build());
    }

    @CommandMethod(arguments = "qty&{count}")
    public SendMessage qty(@Parameter("count") Long count) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("qty:" + count).build();
    }

    @CommandMethod(arguments = "schedule&{date}")
    public SendMessage schedule(@Parameter("date") LocalDate date) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("date:" + date).build();
    }

    @CommandMethod(arguments = "note&{required}&{optional}")
    public SendMessage note(
        @Parameter("required") String required,
        @Parameter(value = "optional", required = false) String optional) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("note:" + required + "/" + optional).build();
    }
}
```
- [ ] Create `EchoCommand.java` (default no-args method + each auto-injection type). Each auto-injected method has a distinct template literal so `MethodMatcher` selects it deterministically by first answer:
```java
package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.commands.dispatcher.annotations.BotCommand;
import com.kb.sessionbot.commands.dispatcher.annotations.CommandMethod;
import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.DynamicParameters;
import com.kb.sessionbot.model.UpdateWrapper;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

@BotCommand(value = "echo", description = "Echo fixture")
public class EchoCommand {

    @CommandMethod
    public SendMessage defaultMethod() {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("default").build();
    }

    @CommandMethod(arguments = "wrapCommand")
    public SendMessage wrapCommand(UpdateWrapper command) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("command:" + command.getCommand()).build();
    }

    @CommandMethod(arguments = "wrapUpdate")
    public SendMessage wrapUpdate(UpdateWrapper update) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("update-wrapper:" + (update == null)).build();
    }

    @CommandMethod(arguments = "rawUpdate")
    public SendMessage rawUpdate(Update update) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("raw-update:" + (update == null)).build();
    }

    @CommandMethod(arguments = "fromUser")
    public SendMessage fromUser(User from) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("from:" + (from == null ? "null" : from.getUserName())).build();
    }

    @CommandMethod(arguments = "chat")
    public SendMessage chat(String chatId) {
        return SendMessage.builder().chatId(chatId).text("chat:" + chatId).build();
    }

    @CommandMethod(arguments = "dyn")
    public SendMessage dyn(DynamicParameters params) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("dyn:" + params.commandApproved()).build();
    }

    @CommandMethod(arguments = "ctx")
    public SendMessage ctx(CommandContext context) {
        return SendMessage.builder().chatId(context.getChatId()).text("ctx:" + context.getCommand()).build();
    }
}
```
- [ ] Create `FixtureCommandConfig.java` (a `@Configuration` that registers the fixture beans plus the dispatch/renderer beans the dispatcher needs, mirroring `CommandsSessionBotConfiguration` but standalone so `CommandsDispatcherTest` gets a real `ApplicationContext` containing `defaultParameterRenderer`):
```java
package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.commands.dispatcher.parameters.BooleanParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.DateParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRendererFactory;
import com.kb.sessionbot.commands.dispatcher.parameters.TextParameterRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FixtureCommandConfig {

    @Bean
    public OrderCommand orderCommand() {
        return new OrderCommand();
    }

    @Bean
    public EchoCommand echoCommand() {
        return new EchoCommand();
    }

    @Bean
    public ParameterRenderer textParameterRenderer() {
        return new TextParameterRenderer();
    }

    @Bean
    public ParameterRenderer dateParameterRenderer() {
        return new DateParameterRenderer();
    }

    @Bean
    public ParameterRenderer booleanParameterRenderer() {
        return new BooleanParameterRenderer();
    }

    @Bean
    public ParameterRenderer defaultParameterRenderer(
        ParameterRenderer textParameterRenderer,
        ParameterRenderer dateParameterRenderer,
        ParameterRenderer booleanParameterRenderer) {
        return new ParameterRendererFactory(textParameterRenderer, dateParameterRenderer, booleanParameterRenderer);
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test` (no fixture-only tests yet; just compile + existing suite green).
- [ ] Commit: `test: add @BotCommand fixtures and DTO builders for dispatch tests`.

---

## Task 2 — CommandBuilderTest

**Files**
- Create: `src/test/java/com/kb/sessionbot/commands/CommandBuilderTest.java`

**Steps**
- [ ] Create `CommandBuilderTest.java`:
```java
package com.kb.sessionbot.commands;

import com.kb.sessionbot.model.MessageDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CommandBuilderTest {

    @Nested
    @DisplayName("build()")
    class Build {

        @Test
        void commandOnly() {
            assertThat(CommandBuilder.create().command("order").build()).isEqualTo("/order");
        }

        @Test
        void commandWithAnswers() {
            assertThat(CommandBuilder.create().command("order").addAnswer("buy").addAnswer("book").build())
                .isEqualTo("/order?buy&book");
        }

        @Test
        void answersOnlyWithoutCommand() {
            assertThat(CommandBuilder.create().addAnswer("buy").addAnswer("book").build())
                .isEqualTo("buy&book");
        }

        @Test
        void typedAnswerOverloads() {
            assertThat(CommandBuilder.create().command("c").addAnswer(5L).addAnswer(true).build())
                .isEqualTo("/c?5&true");
            assertThat(CommandBuilder.create().command("c").addAnswer(LocalDate.of(2026, 6, 2)).build())
                .isEqualTo("/c?2026-06-02");
        }

        @Test
        void singleDynamicParamWithValue() {
            assertThat(CommandBuilder.create().command("c").addParam("k", "v").build())
                .isEqualTo("/c#k:v");
        }

        @Test
        void singleDynamicParamWithoutValueEmitsKeyOnly() {
            assertThat(CommandBuilder.create().command("c").addParam("flag").build())
                .isEqualTo("/c#flag");
        }

        @Test
        void refreshContextFlag() {
            assertThat(CommandBuilder.create().refreshContext().build()).isEqualTo("#refreshContext");
        }

        @Test
        void scipAnswerCarriesIndex() {
            assertThat(CommandBuilder.create().scipAnswer(3).build()).isEqualTo("#scipAnswer:3");
        }

        @Test
        void commandApprovedFlag() {
            assertThat(CommandBuilder.create().commandApproved().build()).isEqualTo("#approved");
        }

        @Test
        void setInitiatorCarriesName() {
            assertThat(CommandBuilder.create().setInitiator("alice").build()).isEqualTo("#initiator:alice");
        }

        @Test
        void combinedCommandAnswersAndSingleParam() {
            assertThat(CommandBuilder.create().command("order").addAnswer("buy").addParam("k", "v").build())
                .isEqualTo("/order?buy#k:v");
        }
    }

    @Nested
    @DisplayName("round-trip with MessageDescriptor")
    class RoundTrip {

        @Test
        void commandAnswersAndParamsSurviveParse() {
            var wire = CommandBuilder.create()
                .command("order")
                .addAnswer("buy")
                .addAnswer("book")
                .addParam("first", "1")
                .addParam("second", "2")
                .build();

            var descriptor = MessageDescriptor.parse(wire);

            assertThat(descriptor.isCommand()).isTrue();
            assertThat(descriptor.getCommand()).isEqualTo("order");
            assertThat(descriptor.getAnswers()).containsExactly("buy", "book");
            assertThat(descriptor.getDynamicParams().getParams())
                .containsEntry("first", "1")
                .containsEntry("second", "2")
                .hasSize(2);
        }

        @Test
        void answersOnlyRoundTrip() {
            var wire = CommandBuilder.create().addAnswer("a").addAnswer("b").build();
            var descriptor = MessageDescriptor.parse(wire);
            assertThat(descriptor.isCommand()).isFalse();
            assertThat(descriptor.getAnswers()).containsExactly("a", "b");
        }

        @Test
        void flagParamRoundTripsToEmptyValue() {
            var wire = CommandBuilder.create().refreshContext().build();
            var descriptor = MessageDescriptor.parse(wire);
            assertThat(descriptor.getDynamicParams().needRefreshContext()).isTrue();
            assertThat(descriptor.getDynamicParams().getParams()).containsEntry("refreshContext", "");
        }
    }

    @Nested
    @DisplayName("64-byte boundary")
    class ByteBoundary {

        @Test
        void atOrUnderLimitProducesExactString() {
            // "/c?" = 3 bytes; pad answer to total 64 bytes.
            var answer = "x".repeat(61);
            var wire = CommandBuilder.create().command("c").addAnswer(answer).build();
            assertThat(wire).isEqualTo("/c?" + answer);
            assertThat(wire.getBytes().length).isEqualTo(64);
        }

        @Test
        void overLimitStillBuildsTheFullString() {
            var answer = "x".repeat(62); // total 65 bytes, triggers the >64 warn path
            var wire = CommandBuilder.create().command("c").addAnswer(answer).build();
            assertThat(wire.getBytes().length).isEqualTo(65);
            assertThat(wire).isEqualTo("/c?" + answer);
        }
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=CommandBuilderTest` green.
- [ ] Commit: `test: characterize CommandBuilder output, round-trip and 64-byte boundary`.

---

## Task 3 — MessageDescriptorTest (rewrite)

**Files**
- Modify (full rewrite): `src/test/java/com/kb/sessionbot/commands/MessageDescriptorTest.java`

**Steps**
- [ ] Replace the file contents entirely with a parameterized + edge-case suite:
```java
package com.kb.sessionbot.commands;

import com.kb.sessionbot.model.MessageDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageDescriptorTest {

    @Nested
    @DisplayName("command detection")
    class CommandDetection {

        @ParameterizedTest(name = "[{index}] \"{0}\" -> command={1}")
        @CsvSource({
            "/order,             true,  order",
            "/order?buy&book,    true,  order",
            "/order#k:v,         true,  order",
            "/order?buy#k:v,     true,  order",
            "order,              false, ",
            "buy&book,           false, ",
            "#k:v,               false, "
        })
        void parsesCommandFlagAndName(String text, boolean isCommand, String expectedCommand) {
            var descriptor = MessageDescriptor.parse(text);
            assertThat(descriptor.isCommand()).isEqualTo(isCommand);
            assertThat(descriptor.getCommand()).isEqualTo(expectedCommand);
        }
    }

    @Nested
    @DisplayName("answers parsing")
    class Answers {

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @CsvSource({
            "/order,            0",
            "/order?,           0",
            "/order?buy,        1",
            "/order?buy&book,   2",
            "/order#k:v,        0",
            "buy&book,          2",
            "#k:v,              0"
        })
        void answerCount(String text, int expectedCount) {
            assertThat(MessageDescriptor.parse(text).getAnswers()).hasSize(expectedCount);
        }

        @Test
        void commandWithAnswersAndParams() {
            var descriptor = MessageDescriptor.parse("/order?buy&book#k:v");
            assertThat(descriptor.getAnswers()).containsExactly("buy", "book");
        }

        @Test
        void answersOnlyWithoutLeadingSlash() {
            var descriptor = MessageDescriptor.parse("buy&book");
            assertThat(descriptor.isCommand()).isFalse();
            assertThat(descriptor.getAnswers()).containsExactly("buy", "book");
        }

        @Test
        void trailingArgumentSeparatorYieldsNoAnswers() {
            // "/order?".split("\\?") -> ["/order"], length 1 -> empty answers.
            assertThat(MessageDescriptor.parse("/order?").getAnswers()).isEmpty();
        }
    }

    @Nested
    @DisplayName("dynamic params parsing")
    class DynamicParams {

        @Test
        void noParamsYieldsEmptyMap() {
            assertThat(MessageDescriptor.parse("/order?buy").getDynamicParams().getParams()).isEmpty();
        }

        @Test
        void singleParamWithValue() {
            assertThat(MessageDescriptor.parse("/order#k:v").getDynamicParams().getParams())
                .containsExactlyEntriesOf(java.util.Map.of("k", "v"));
        }

        @Test
        void paramWithoutValueBecomesEmptyString() {
            assertThat(MessageDescriptor.parse("/order#refreshContext").getDynamicParams().getParams())
                .containsEntry("refreshContext", "");
        }

        @Test
        void multipleDynamicParams() {
            assertThat(MessageDescriptor.parse("/order#a:1&b:2&flag").getDynamicParams().getParams())
                .containsEntry("a", "1")
                .containsEntry("b", "2")
                .containsEntry("flag", "")
                .hasSize(3);
        }

        @Test
        void paramsOnlyWithoutCommandOrAnswers() {
            var descriptor = MessageDescriptor.parse("#k:v");
            assertThat(descriptor.isCommand()).isFalse();
            assertThat(descriptor.getCommand()).isNull();
            assertThat(descriptor.getAnswers()).isEmpty();
            assertThat(descriptor.getDynamicParams().getParams()).containsEntry("k", "v");
        }
    }

    @Nested
    @DisplayName("guard cases")
    class Guards {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   "})
        void blankInputThrows(String text) {
            assertThatThrownBy(() -> MessageDescriptor.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("text is empty");
        }
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=MessageDescriptorTest` green.
- [ ] Commit: `test: rewrite MessageDescriptorTest as parameterized suite with edge cases`.

---

## Task 4 — DynamicParametersTest

**Files**
- Create: `src/test/java/com/kb/sessionbot/model/DynamicParametersTest.java`

**Steps**
- [ ] Create `DynamicParametersTest.java`:
```java
package com.kb.sessionbot.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicParametersTest {

    @Test
    @DisplayName("empty() has no params and reports false for every flag")
    void emptyHasNoParams() {
        var params = DynamicParameters.empty();
        assertThat(params.isEmpty()).isTrue();
        assertThat(params.needRefreshContext()).isFalse();
        assertThat(params.commandApproved()).isFalse();
        assertThat(params.canScipAnswer(0)).isFalse();
        assertThat(params.getInitiator()).isNull();
    }

    @Nested
    @DisplayName("canScipAnswer")
    class CanScip {

        @Test
        void falseWhenScipKeyAbsent() {
            assertThat(DynamicParameters.create(Map.of("other", "1")).canScipAnswer(0)).isFalse();
        }

        @ParameterizedTest(name = "scipAnswer={0}, query index={1} -> {2}")
        @CsvSource({
            "2, 0, true",
            "2, 1, true",
            "2, 2, true",
            "2, 3, false",
            "0, 0, true",
            "0, 1, false"
        })
        void allowsSkipWhenAllowedIndexAtLeastQueried(String allowed, int index, boolean expected) {
            var params = DynamicParameters.create(Map.of("scipAnswer", allowed));
            assertThat(params.canScipAnswer(index)).isEqualTo(expected);
        }
    }

    @Test
    void needRefreshContextIsKeyPresence() {
        assertThat(DynamicParameters.create(Map.of("refreshContext", "")).needRefreshContext()).isTrue();
        assertThat(DynamicParameters.create(Map.of("x", "y")).needRefreshContext()).isFalse();
    }

    @Test
    void commandApprovedIsKeyPresence() {
        assertThat(DynamicParameters.create(Map.of("approved", "")).commandApproved()).isTrue();
        assertThat(DynamicParameters.create(Map.of("x", "y")).commandApproved()).isFalse();
    }

    @Test
    void getInitiatorReturnsRawValueOrNull() {
        assertThat(DynamicParameters.create(Map.of("initiator", "alice")).getInitiator()).isEqualTo("alice");
        assertThat(DynamicParameters.create(Map.of("x", "y")).getInitiator()).isNull();
    }

    @Test
    void hasParamAndGetParam() {
        var params = DynamicParameters.create(Map.of("k", "v"));
        assertThat(params.hasParam("k")).isTrue();
        assertThat(params.hasParam("missing")).isFalse();
        assertThat(params.getParam("k")).isEqualTo("v");
        assertThat(params.getParam("missing")).isNull();
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=DynamicParametersTest` green.
- [ ] Commit: `test: characterize DynamicParameters flag and scip-answer logic`.

---

## Task 5 — CommandContextTest

**Files**
- Create: `src/test/java/com/kb/sessionbot/model/CommandContextTest.java`

**Steps**
- [ ] Create `CommandContextTest.java`. Uses `Fixtures` for wrappers:
```java
package com.kb.sessionbot.model;

import com.kb.sessionbot.fixtures.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandContextTest {

    @Nested
    @DisplayName("creation guards")
    class Guards {

        @Test
        void createRejectsNonCommandUpdate() {
            var answer = Fixtures.commandWrapper("buy&book"); // no leading '/', not a command
            assertThatThrownBy(() -> CommandContext.create(answer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Context should be created only for command.");
        }

        @Test
        void addUpdateRejectsCommandUpdate() {
            var context = CommandContext.create(Fixtures.commandWrapper("/order"));
            var command = Fixtures.commandWrapper("/order");
            assertThatThrownBy(() -> context.addUpdate(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Command should create new context");
        }
    }

    @Test
    @DisplayName("empty() is empty and create() is open")
    void emptyAndCreateState() {
        assertThat(CommandContext.empty().isEmpty()).isTrue();
        var context = CommandContext.create(Fixtures.commandWrapper("/order"));
        assertThat(context.isEmpty()).isFalse();
        assertThat(context.getState()).isEqualTo(ContextState.open);
        assertThat(context.getCommand()).isEqualTo("order");
    }

    @Test
    @DisplayName("getAnswers merges command answers and pending arguments")
    void answersMerge() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order?buy"));
        assertThat(context.getAnswers()).containsExactly("buy");

        context.addUpdate(Fixtures.answerWrapper(2, 100, "book"));
        assertThat(context.getAnswers()).containsExactly("buy", "book");
        assertThat(context.getPendingArguments()).containsExactly("book");
    }

    @Test
    @DisplayName("lifecycle open -> progress -> close")
    void lifecycle() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order"));
        assertThat(context.getState()).isEqualTo(ContextState.open);
        context.startProgress();
        assertThat(context.getState()).isEqualTo(ContextState.progress);
        context.close();
        assertThat(context.getState()).isEqualTo(ContextState.close);
    }

    @Test
    @DisplayName("getChatId falls back from command update to current update, null when empty")
    void chatId() {
        assertThat(CommandContext.empty().getChatId()).isNull();
        var context = CommandContext.create(Fixtures.commandWrapper("/order"));
        assertThat(context.getChatId()).isEqualTo(String.valueOf(Fixtures.CHAT_ID));
    }

    @Test
    @DisplayName("refreshContext rebuild keeps command answers and re-applies the latest update")
    void refreshRebuildSemantics() {
        // Mirrors CommandsSessionBot.handleUpdates: rebuild from the original command update,
        // then re-add the current (refresh) update.
        var command = Fixtures.commandWrapper("/order?buy");
        var refreshUpdate = Fixtures.answerWrapper(2, 100, "book#refreshContext");
        var rebuilt = CommandContext.create(command).addUpdate(refreshUpdate);

        assertThat(rebuilt.getCommand()).isEqualTo("order");
        assertThat(rebuilt.getAnswers()).containsExactly("buy", "book");
        assertThat(rebuilt.getDynamicParams().needRefreshContext()).isTrue();
    }

    @Test
    @DisplayName("getDynamicParams reads the current update, falling back to the command update")
    void dynamicParamsFallback() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order#approved"));
        assertThat(context.getDynamicParams().commandApproved()).isTrue();

        context.addUpdate(Fixtures.answerWrapper(2, 100, "book#initiator:alice"));
        assertThat(context.getDynamicParams().getInitiator()).isEqualTo("alice");
    }

    @Test
    @DisplayName("addQuestionMessage rejects null and records the message")
    void questionMessages() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order"));
        assertThatThrownBy(() -> context.addQuestionMessage(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("Message is null");
        var msg = Fixtures.message(Fixtures.CHAT_ID, 555, "question");
        context.addQuestionMessage(msg);
        assertThat(context.getQuestionMessages()).containsExactly(msg);
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=CommandContextTest` green.
- [ ] Commit: `test: characterize CommandContext lifecycle, answer merge and refresh rebuild`.

---

## Task 6 — UpdateWrapperTest

**Files**
- Create: `src/test/java/com/kb/sessionbot/model/UpdateWrapperTest.java`

**Steps**
- [ ] Create `UpdateWrapperTest.java`:
```java
package com.kb.sessionbot.model;

import com.kb.sessionbot.fixtures.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateWrapperTest {

    @Test
    @DisplayName("wrap rejects null update")
    void wrapNull() {
        assertThatThrownBy(() -> UpdateWrapper.wrap(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("Update is null.");
    }

    @Test
    @DisplayName("wrap of update with neither message nor callback throws on empty text parse")
    void wrapEmptyTextThrows() {
        var update = new Update();
        update.setUpdateId(1);
        assertThatThrownBy(() -> UpdateWrapper.wrap(update))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("text is empty");
    }

    @Nested
    @DisplayName("getChatId")
    class ChatId {

        @Test
        void fromMessage() {
            var wrapper = UpdateWrapper.wrap(Fixtures.messageUpdate(1, 99L, 10, "/order"));
            assertThat(wrapper.getChatId()).isEqualTo("99");
        }

        @Test
        void fromCallbackQuery() {
            var wrapper = UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 77L, 10, "book"));
            assertThat(wrapper.getChatId()).isEqualTo("77");
        }
    }

    @Nested
    @DisplayName("isCommand OR-logic")
    class IsCommand {

        @Test
        void trueWhenDescriptorIsCommand() {
            assertThat(UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "/order")).isCommand()).isTrue();
        }

        @Test
        void falseForPlainText() {
            assertThat(UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "book")).isCommand()).isFalse();
        }

        @Test
        void trueWhenCallbackDataIsCommand() {
            assertThat(UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 1, "/order")).isCommand()).isTrue();
        }

        @Test
        void falseForCallbackAnswerData() {
            assertThat(UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 1, "book")).isCommand()).isFalse();
        }
    }

    @Nested
    @DisplayName("getFrom")
    class From {

        @Test
        void fromMessage() {
            var wrapper = UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "/order"));
            assertThat(wrapper.getFrom().getUserName()).isEqualTo("tester");
        }

        @Test
        void fromCallback() {
            var wrapper = UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 1, "book"));
            assertThat(wrapper.getFrom().getUserName()).isEqualTo("tester");
        }
    }

    @Test
    @DisplayName("getCallbackMessage present for callback, empty for message")
    void callbackMessage() {
        var callbackWrapper = UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 33, "book"));
        assertThat(callbackWrapper.getCallbackMessage()).isPresent();
        assertThat(callbackWrapper.getCallbackMessage().get().getMessageId()).isEqualTo(33);

        var messageWrapper = UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "/order"));
        assertThat(messageWrapper.getCallbackMessage()).isEmpty();
    }

    @Test
    @DisplayName("getMessageId present for message, empty for callback")
    void messageId() {
        assertThat(UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 12, "/order")).getMessageId()).contains(12);
        assertThat(UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 1, "book")).getMessageId()).isEmpty();
    }

    @Test
    @DisplayName("getAnswers and getCommand delegate to descriptor")
    void delegation() {
        var wrapper = UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "/order?buy&book"));
        assertThat(wrapper.getCommand()).isEqualTo("order");
        assertThat(wrapper.getAnswers()).containsExactly("buy", "book");
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=UpdateWrapperTest` green.
- [ ] Commit: `test: characterize UpdateWrapper adaptation of message and callback updates`.

---

## Task 7 — MethodMatcherTest

**Files**
- Create: `src/test/java/com/kb/sessionbot/commands/dispatcher/MethodMatcherTest.java`

**Steps**
- [ ] Create `MethodMatcherTest.java`. Builds a `MethodMatcher` from the `OrderCommand`/`EchoCommand` fixtures via `MethodMatcher.create(...)` and asserts which template the context's answers select. Selected method is identified by `getArguments()`:
```java
package com.kb.sessionbot.commands.dispatcher;

import com.kb.sessionbot.fixtures.EchoCommand;
import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.fixtures.OrderCommand;
import com.kb.sessionbot.model.CommandContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MethodMatcherTest {

    private final MethodMatcher orderMatcher = MethodMatcher.create(new OrderCommand());
    private final MethodMatcher echoMatcher = MethodMatcher.create(new EchoCommand());

    private static CommandContext contextWith(String wire) {
        return CommandContext.create(Fixtures.commandWrapper(wire));
    }

    @Nested
    @DisplayName("literal + placeholder matching")
    class Matching {

        @Test
        void literalThenPlaceholderMatchesTemplate() {
            var match = orderMatcher.getMatchingMethod(contextWith("/order?buy&book"));
            assertThat(match).isPresent();
            assertThat(match.get().getArguments()).isEqualTo("buy&{product}");
        }

        @Test
        void literalPrefixSelectsAmongCompetingTemplates() {
            var match = orderMatcher.getMatchingMethod(contextWith("/order?qty&5"));
            assertThat(match).isPresent();
            assertThat(match.get().getArguments()).isEqualTo("qty&{count}");
        }

        @Test
        void partialAnswersStillMatchByLiteralPrefix() {
            // Only the literal answer present; placeholder not yet supplied.
            var match = orderMatcher.getMatchingMethod(contextWith("/order?buy"));
            assertThat(match).isPresent();
            assertThat(match.get().getArguments()).isEqualTo("buy&{product}");
        }

        @Test
        void unknownLiteralMatchesNothing() {
            assertThat(orderMatcher.getMatchingMethod(contextWith("/order?unknown"))).isEmpty();
        }

        @Test
        void tooManyAnswersFilteredOutByTemplateSize() {
            // 3 answers but the buy template has size 2 -> filtered by args.size() <= template.size().
            assertThat(orderMatcher.getMatchingMethod(contextWith("/order?buy&book&extra"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("default method")
    class DefaultMethod {

        @Test
        void selectedWhenNoAnswers() {
            var match = echoMatcher.getMatchingMethod(contextWith("/echo"));
            assertThat(match).isPresent();
            assertThat(match.get().getArguments()).isEqualTo("");
            assertThat(match.get().isDefaultMethod()).isTrue();
        }

        @Test
        void notSelectedWhenAnswersPresent() {
            var match = echoMatcher.getMatchingMethod(contextWith("/echo?wrapCommand"));
            assertThat(match).isPresent();
            assertThat(match.get().getArguments()).isEqualTo("wrapCommand");
        }
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=MethodMatcherTest` green.
- [ ] Commit: `test: characterize MethodMatcher scoring and default-method selection`.

---

## Task 8 — CommandsDispatcherTest

**Files**
- Create: `src/test/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcherTest.java`

**Steps**
- [ ] Create `CommandsDispatcherTest.java`. It boots a minimal Spring context from `FixtureCommandConfig` (so `getBean("defaultParameterRenderer", ...)` resolves), constructs a `CommandsDispatcher` per fixture bean, and asserts `invoke(...)` outcomes. `StepVerifier` checks the produced `SendMessage` text; missing-required produces an `invocationArgument` (the renderer prompt) instead of an invocation:
```java
package com.kb.sessionbot.commands.dispatcher;

import com.kb.sessionbot.fixtures.EchoCommand;
import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.fixtures.FixtureCommandConfig;
import com.kb.sessionbot.fixtures.OrderCommand;
import com.kb.sessionbot.model.CommandContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class CommandsDispatcherTest {

    private AnnotationConfigApplicationContext context;
    private CommandsDispatcher orderDispatcher;
    private CommandsDispatcher echoDispatcher;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(FixtureCommandConfig.class);
        orderDispatcher = new CommandsDispatcher(context.getBean(OrderCommand.class), context);
        echoDispatcher = new CommandsDispatcher(context.getBean(EchoCommand.class), context);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static CommandContext ctx(String wire) {
        return CommandContext.create(Fixtures.commandWrapper(wire));
    }

    @Nested
    @DisplayName("Jackson parameter binding")
    class Binding {

        @Test
        void bindsString() {
            var result = orderDispatcher.invoke(ctx("/order?buy&book"));
            assertThat(result.hasErrors()).isFalse();
            assertThat(result.getInvocationArgument()).isNull();
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("buy:book"))
                .verifyComplete();
        }

        @Test
        void bindsLong() {
            var result = orderDispatcher.invoke(ctx("/order?qty&5"));
            assertThat(result.hasErrors()).isFalse();
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("qty:5"))
                .verifyComplete();
        }

        @Test
        void bindsLocalDateViaJavaTimeModule() {
            var result = orderDispatcher.invoke(ctx("/order?schedule&2026-06-02"));
            assertThat(result.hasErrors()).isFalse();
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("date:2026-06-02"))
                .verifyComplete();
        }
    }

    @Nested
    @DisplayName("optional parameter handling")
    class Optional {

        @Test
        void missingRequiredRendersPrompt() {
            // "note&{required}&{optional}" with no required answer -> renderer prompt, no invocation.
            var result = orderDispatcher.invoke(ctx("/order?note"));
            assertThat(result.getInvocation()).isNull();
            assertThat(result.getInvocationArgument()).isNotNull();
            StepVerifier.create(result.getInvocationArgument())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).contains("required"))
                .verifyComplete();
        }

        @Test
        void optionalSkippedWhenScipAnswerSet() {
            // required supplied; optional missing but scipAnswer allows skipping index 2.
            var result = orderDispatcher.invoke(ctx("/order?note&hello#scipAnswer:2"));
            assertThat(result.hasErrors()).isFalse();
            assertThat(result.getInvocationArgument()).isNull();
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("note:hello/null"))
                .verifyComplete();
        }
    }

    @Nested
    @DisplayName("auto-injection")
    class AutoInjection {

        @Test
        void updateWrapperCommand() {
            var result = echoDispatcher.invoke(ctx("/echo?wrapCommand"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("command:echo"))
                .verifyComplete();
        }

        @Test
        void updateWrapperUpdateIsNullWithoutCurrentUpdate() {
            // No follow-up update added, so current update is absent -> null UpdateWrapper.
            var result = echoDispatcher.invoke(ctx("/echo?wrapUpdate"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("update-wrapper:true"))
                .verifyComplete();
        }

        @Test
        void rawUpdateIsNullWithoutCurrentUpdate() {
            var result = echoDispatcher.invoke(ctx("/echo?rawUpdate"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("raw-update:true"))
                .verifyComplete();
        }

        @Test
        void userFrom() {
            var result = echoDispatcher.invoke(ctx("/echo?fromUser"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("from:tester"))
                .verifyComplete();
        }

        @Test
        void chatIdString() {
            var result = echoDispatcher.invoke(ctx("/echo?chat"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("chat:" + Fixtures.CHAT_ID))
                .verifyComplete();
        }

        @Test
        void dynamicParameters() {
            var result = echoDispatcher.invoke(ctx("/echo?dyn#approved"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("dyn:true"))
                .verifyComplete();
        }

        @Test
        void commandContext() {
            var result = echoDispatcher.invoke(ctx("/echo?ctx"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("ctx:echo"))
                .verifyComplete();
        }
    }

    @Nested
    @DisplayName("no method match")
    class NoMatch {

        @Test
        void rendersDefaultPromptAndNoInvocation() {
            var result = orderDispatcher.invoke(ctx("/order?unsupported"));
            assertThat(result.getInvocation()).isNull();
            assertThat(result.getInvocationArgument()).isNotNull();
            StepVerifier.create(result.getInvocationArgument())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).contains("order"))
                .verifyComplete();
        }
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=CommandsDispatcherTest` green.
- [ ] Commit: `test: characterize CommandsDispatcher binding, auto-injection and prompts`.

---

## Task 9 — CommandsSessionBotTest

**Files**
- Create: `src/test/java/com/kb/sessionbot/CommandsSessionBotTest.java`

**Steps**
- [ ] Create `CommandsSessionBotTest.java`. Builds a `CommandsSessionBot` directly (no Spring) with a real `CommandsFactory` over the fixture commands wrapped in `DispatcherBotCommand`, a real `ErrorHandlerFactory`, a controllable `AuthInterceptor`, mocked `TelegramClient` (returns a `Message` from `execute(SendMessage)` so the progress side-effect is exercised), and drives the **package-private** `handleUpdates` with `StepVerifier`. A separate test drives `consume()` end-to-end:
```java
package com.kb.sessionbot;

import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.commands.HelpCommand;
import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.commands.dispatcher.DispatcherBotCommand;
import com.kb.sessionbot.config.CommandsSessionBotProperties;
import com.kb.sessionbot.errors.exception.BotAuthException;
import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import com.kb.sessionbot.fixtures.EchoCommand;
import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.fixtures.FixtureCommandConfig;
import com.kb.sessionbot.fixtures.OrderCommand;
import com.kb.sessionbot.model.UpdateWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class CommandsSessionBotTest {

    private AnnotationConfigApplicationContext springContext;
    private TelegramClient telegramClient;
    private CommandsFactory commandsFactory;
    private ErrorHandlerFactory errorHandlerFactory;

    @BeforeEach
    void setUp() throws Exception {
        springContext = new AnnotationConfigApplicationContext(FixtureCommandConfig.class);

        List<IBotCommand> commands = List.of(
            new DispatcherBotCommand(springContext.getBean(OrderCommand.class), springContext),
            new DispatcherBotCommand(springContext.getBean(EchoCommand.class), springContext));
        var helpCommand = new HelpCommand(commands);
        commandsFactory = new CommandsFactory(helpCommand, commands);
        commandsFactory.start();

        errorHandlerFactory = new ErrorHandlerFactory(
            List.<ErrorHandler<?>>of(new BotCommandErrorHandler(), new BotAuthErrorHandler()));
        errorHandlerFactory.init();

        telegramClient = Mockito.mock(TelegramClient.class);
        // execute(SendMessage) returns a Message so the progress branch records a question message.
        Mockito.when(telegramClient.execute(any(BotApiMethod.class)))
            .thenReturn(Fixtures.message(Fixtures.CHAT_ID, 999, "sent"));
    }

    @AfterEach
    void tearDown() {
        springContext.close();
    }

    private CommandsSessionBot bot(AuthInterceptor auth) {
        return new CommandsSessionBot(
            commandsFactory, auth, errorHandlerFactory,
            new CommandsSessionBotProperties(), telegramClient);
    }

    private static final AuthInterceptor ALLOW = ctx -> reactor.core.publisher.Mono.just(true);
    private static final AuthInterceptor DENY = ctx -> reactor.core.publisher.Mono.just(false);

    @DisplayName("command update completes and emits SendMessage then DeleteMessage cleanup")
    @Test
    void commandStartsFreshContext() {
        var bot = bot(ALLOW);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")));

        StepVerifier.create(bot.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).isEqualTo("buy:book");
            })
            .assertNext(m -> assertThat(m).isInstanceOf(DeleteMessage.class))
            .verifyComplete();
    }

    @DisplayName("non-command answer appends to the in-progress context and completes it")
    @Test
    void nonCommandAppendsToContext() {
        var bot = bot(ALLOW);
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
            Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book")));

        // First fold step -> "buy" only -> prompt (progress, executed as side effect).
        // Second fold step -> appends "book" -> invocation "buy:book" + cleanup.
        StepVerifier.create(bot.handleUpdates(updates).filter(m -> m instanceof SendMessage)
                .map(m -> ((SendMessage) m).getText()))
            .expectNextMatches(text -> text.contains("product") || text.equals("buy:book"))
            .thenConsumeWhile(text -> !text.equals("buy:book"))
            .verifyComplete();
    }

    @DisplayName("empty context routes to HelpCommand")
    @Test
    void emptyContextUsesHelp() {
        var bot = bot(ALLOW);
        // A non-command first update with no in-progress context stays empty -> help.
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "book")));

        StepVerifier.create(bot.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).contains("Помощь");
            })
            .verifyComplete();
    }

    @DisplayName("refreshContext rebuilds the context from the original command")
    @Test
    void refreshContextRebuild() {
        var bot = bot(ALLOW);
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
            Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book#refreshContext")));

        // After refresh the context is rebuilt from "/order?buy" then "book" re-applied -> "buy:book".
        StepVerifier.create(bot.handleUpdates(updates)
                .filter(m -> m instanceof SendMessage)
                .map(m -> ((SendMessage) m).getText()))
            .thenConsumeWhile(text -> !text.equals("buy:book"))
            .verifyComplete();
    }

    @DisplayName("auth rejection surfaces BotAuthException")
    @Test
    void authRejectSurfacesError() {
        var bot = bot(DENY);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")));

        StepVerifier.create(bot.handleUpdates(updates))
            .expectError(BotAuthException.class)
            .verify();
    }

    @DisplayName("progress state records the question message via addQuestionMessage side effect")
    @Test
    void progressRecordsQuestionMessage() {
        var bot = bot(ALLOW);
        // Single answer -> command stays in progress and prompts; the prompt SendMessage is executed.
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")));

        StepVerifier.create(bot.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).contains("product");
            })
            .verifyComplete();

        // The prompt was executed against the client (progress branch).
        verify(telegramClient, timeout(2000)).execute(any(BotApiMethod.class));
    }

    @DisplayName("consume() drives an update end-to-end to telegramClient.execute")
    @Test
    void consumeEndToEnd() throws Exception {
        var bot = bot(ALLOW);
        bot.init(); // wires the reactive pipeline and emits SetMyCommands
        bot.consume(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book"));

        // SetMyCommands at startup + SendMessage + DeleteMessage from the completed command.
        verify(telegramClient, timeout(2000).atLeast(2)).execute(any(BotApiMethod.class));
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=CommandsSessionBotTest` green. If the `nonCommandAppends`/`refreshContextRebuild` consume-while assertions prove timing-sensitive against the real fold, keep them as the documented behavior (single subscription, sequential fold) — they assert the terminal `buy:book` emission appears, which is the locked behavior.
- [ ] Commit: `test: characterize handleUpdates fold and consume end-to-end flow`.

---

## Task 10 — Extend CommandsSessionBotAutoConfigurationTest

**Files**
- Modify: `src/test/java/com/kb/sessionbot/config/CommandsSessionBotAutoConfigurationTest.java`

**Steps**
- [ ] Add imports for the broadened bean assertions and the second override. At the top of the file add:
```java
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRenderer;
import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
```
- [ ] Replace the body of `activatesWhenTelegramPropertiesPresent` with broadened assertions over the real `@ConditionalOnMissingBean` defaults:
```java
    @Test
    void activatesWhenTelegramPropertiesPresent() {
        activeRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommandsSessionBot.class);
            assertThat(context).hasSingleBean(HelpCommand.class);
            assertThat(context).hasSingleBean(CommandsFactory.class);
            assertThat(context).hasSingleBean(AuthInterceptor.class);
            assertThat(context).hasSingleBean(ErrorHandlerFactory.class);
            assertThat(context).hasSingleBean(BotCommandErrorHandler.class);
            assertThat(context).hasSingleBean(BotAuthErrorHandler.class);
            assertThat(context).hasBean("defaultParameterRenderer");
            assertThat(context).hasBean("textParameterRenderer");
            assertThat(context).hasBean("booleanParameterRenderer");
            assertThat(context).hasBean("dateParameterRenderer");
            assertThat(context.getBeansOfType(ParameterRenderer.class)).hasSize(4);
        });
    }
```
- [ ] After `allowsDownstreamToOverrideConditionalBeans`, add a second override test for the default parameter renderer (a named-bean override):
```java
    @Test
    void allowsDownstreamToOverrideDefaultParameterRenderer() {
        ParameterRenderer custom = request -> Mono.empty();
        activeRunner.withBean("defaultParameterRenderer", ParameterRenderer.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("defaultParameterRenderer", ParameterRenderer.class)).isSameAs(custom);
        });
    }
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=CommandsSessionBotAutoConfigurationTest` green.
- [ ] Commit: `test: broaden auto-configuration default-bean and override assertions`.

---

## Task 11 — ErrorHandlerFactoryTest

**Files**
- Create: `src/test/java/com/kb/sessionbot/errors/handler/ErrorHandlerFactoryTest.java`

**Steps**
- [ ] Create `ErrorHandlerFactoryTest.java`. Registers the two real handlers, calls `init()` (the `@PostConstruct` that builds the type→handler map via reflection), and asserts routing. `BotAuthErrorHandler` emits the exception's own message; `BotCommandErrorHandler` emits the **root-cause** message; unmatched types yield `Mono.empty()`:
```java
package com.kb.sessionbot.errors.handler;

import com.kb.sessionbot.errors.exception.BotAuthException;
import com.kb.sessionbot.errors.exception.BotCommandException;
import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.model.CommandContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlerFactoryTest {

    private CommandContext context;
    private ErrorHandlerFactory factory;

    @BeforeEach
    void setUp() {
        context = Fixtures.contextFor("/order");
        factory = new ErrorHandlerFactory(
            List.<ErrorHandler<?>>of(new BotCommandErrorHandler(), new BotAuthErrorHandler()));
        factory.init();
    }

    @Test
    @DisplayName("routes BotAuthException to its handler, emitting the exception message")
    void routesAuthException() {
        StepVerifier.create(factory.handle(new BotAuthException(context, "denied")))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).isEqualTo("denied");
                assertThat(((SendMessage) m).getChatId()).isEqualTo(String.valueOf(Fixtures.CHAT_ID));
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("walks the cause chain root-outward and routes BotCommandException to its handler (root-cause message)")
    void routesCommandExceptionUsingRootCause() {
        // getThrowableList = [BotCommandException, IllegalStateException]; reversed walk checks the
        // root (IllegalStateException, no handler) first, then matches the outer BotCommandException.
        var exception = new BotCommandException(context, new IllegalStateException("boom"));
        StepVerifier.create(factory.handle(exception))
            .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("boom"))
            .verifyComplete();
    }

    @Test
    @DisplayName("unhandled exception type yields empty (swallowed)")
    void noHandlerYieldsEmpty() {
        StepVerifier.create(factory.handle(new IllegalStateException("unmapped")))
            .verifyComplete();
    }
}
```
- [ ] Run `mvn -q test-compile` → expect BUILD SUCCESS.
- [ ] **Acceptance (owner-run):** `mvn test -Dtest=ErrorHandlerFactoryTest` green.
- [ ] Commit: `test: characterize ErrorHandlerFactory cause-chain routing`.

---

## Final acceptance

- [ ] **Owner-run:** `mvn test` — full suite green.
- [ ] Coverage sanity check (manual review against the spec): `CommandsDispatcher`, `MethodMatcher`, `CommandBuilder`, `MessageDescriptor`, `CommandContext`, `UpdateWrapper`, `DynamicParameters`, `ErrorHandlerFactory` (directly in `ErrorHandlerFactoryTest`, plus the auth-reject path in `CommandsSessionBotTest`), and `handleUpdates` each have the spec's behaviors asserted.
- [ ] Confirm no test asserts a group-B buggy path (no concurrency/ordering-under-contention, no duplicate-template resolution, no `sendMessage` thread-safety).