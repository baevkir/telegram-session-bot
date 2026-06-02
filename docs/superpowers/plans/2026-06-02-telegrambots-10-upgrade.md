# telegrambots 10.0.0 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the library off the dead `org.telegram:telegrambots` 6.8.0 monolith onto the modular 10.0.0 architecture, resolving the remaining `grizzly-http` and `commons-io` CVEs.

**Architecture:** Replace `TelegramLongPollingBot` (extends) with a `LongPollingSingleThreadUpdateConsumer` (implements) that sends via an injected OkHttp-based `TelegramClient`; replace the `TelegramBotsApi`/`DefaultBotSession.registerBot` wiring with `TelegramBotsLongPollingApplication.registerBot(token, consumer)`; migrate inline-keyboard construction from `List<List<InlineKeyboardButton>>` to `List<InlineKeyboardRow>`.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, Project Reactor, `org.telegram:telegrambots-{meta,client,longpolling}:10.0.0`.

**Spec:** `docs/superpowers/specs/2026-06-02-telegrambots-10-upgrade-design.md`

---

## Notes for the executor

- **This is an API migration, not new behavior — TDD does not apply.** The safety net is (a) compilation and (b) the existing auto-config test suite. The work proceeds as: migrate all interdependent source in one atomic, compiling commit, then update tests, then docs, then verify.
- **Expect compilation to fail mid-migration.** Removing `TelegramLongPollingBot` breaks `CommandsSessionBot`, which breaks the config, etc. Do not commit until `mvn test-compile` is green at the end of Task 1. That single commit is the correct granularity — a half-migration is not independently useful.
- **Test execution convention:** this repo's owner runs the full test suite (`mvn test`) themselves. The plan uses `mvn test-compile` for automated compile checks; `mvn test` appears only as the final acceptance gate (Task 4), to be run/confirmed by the owner.
- **Branch:** work happens on `telegrambots-10-upgrade` (already checked out, already contains the design spec commit).

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `pom.xml` | dependencies | swap telegrambots artifacts to 10.0.0 |
| `src/main/java/com/kb/sessionbot/CommandsSessionBot.java` | the bot: update consumption + sending | implements consumer, holds `TelegramClient` |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java` | auto-config wiring | client bean + long-polling app bean |
| `src/main/java/com/kb/sessionbot/commands/presenter/AbstractMessagePresenter.java` | base presenter keyboards | `List<InlineKeyboardRow>` |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/TextParameterRenderer.java` | text param keyboard | `InlineKeyboardRow` |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/BooleanParameterRenderer.java` | boolean param keyboard | `InlineKeyboardRow` |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/DateParameterRenderer.java` | date picker keyboard | `InlineKeyboardRow` |
| `src/test/java/com/kb/sessionbot/config/CommandsSessionBotAutoConfigurationTest.java` | auto-config tests | mock new beans |
| `CLAUDE.md` | docs | new artifacts + consumer/client model |

---

### Task 1: Migrate all production source to the 10.0.0 API

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`
- Modify: `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java`
- Modify: `src/main/java/com/kb/sessionbot/commands/presenter/AbstractMessagePresenter.java`
- Modify: `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/TextParameterRenderer.java`
- Modify: `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/BooleanParameterRenderer.java`
- Modify: `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/DateParameterRenderer.java`

- [ ] **Step 1: Swap dependencies in `pom.xml`**

Bump the version property:
```xml
<telegram-bot.version>10.0.0</telegram-bot.version>
```

Replace the three telegram dependencies (remove `telegrambotsextensions`, remove the
`jackson-module-jaxb-annotations` exclusion, replace the monolith with the modular trio):
```xml
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-meta</artifactId>
    <version>${telegram-bot.version}</version>
</dependency>
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-client</artifactId>
    <version>${telegram-bot.version}</version>
</dependency>
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-longpolling</artifactId>
    <version>${telegram-bot.version}</version>
</dependency>
```
(Delete the old `<dependency>` blocks for `telegrambots`, `telegrambots-meta` 6.8.0, and
`telegrambotsextensions`, including the `<exclusions>` that was on `telegrambots`.)

- [ ] **Step 2: Rewrite `CommandsSessionBot.java`**

Change imports — remove:
```java
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
```
add:
```java
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.generics.TelegramClient;
```

Change the class declaration:
```java
public class CommandsSessionBot implements LongPollingSingleThreadUpdateConsumer {
```

Add the client field (next to the other final fields):
```java
    private final TelegramClient telegramClient;
```

Replace the constructor with one that accepts the client:
```java
    public CommandsSessionBot(
        CommandsFactory commandsFactory,
        AuthInterceptor authInterceptor,
        ErrorHandlerFactory errorHandler,
        CommandsSessionBotProperties properties,
        TelegramClient telegramClient
    ) {
        this.commandsFactory = commandsFactory;
        this.errorHandler = errorHandler;
        this.authInterceptor = authInterceptor;
        this.properties = properties;
        this.telegramClient = telegramClient;
    }
```

Replace `onUpdateReceived` and delete the `getBotToken()`/`getBotUsername()` overrides.
Replace this block:
```java
    @Override
    public void onUpdateReceived(Update update) {
        updatesSink.tryEmitNext(update);
    }

    @Override
    public String getBotToken() {
        return properties.getToken();
    }

    @Override
    public String getBotUsername() {
        return properties.getBotUsername();
    }
```
with:
```java
    @Override
    public void consume(Update update) {
        updatesSink.tryEmitNext(update);
    }
```

In `executeMessage`, swap the inherited `execute` for the client. Change:
```java
                return execute((BotApiMethod<T>) message);
```
to:
```java
                return telegramClient.execute((BotApiMethod<T>) message);
```

- [ ] **Step 3: Rewrite the bot wiring in `CommandsSessionBotConfiguration.java`**

Change imports — remove:
```java
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
```
add:
```java
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;
```
(keep `import org.telegram.telegrambots.meta.exceptions.TelegramApiException;`)

Replace the `telegramBotsApi(...)` bean with a `telegramClient` bean and a long-polling
application bean. Delete:
```java
    @Bean
    @ConditionalOnMissingBean
    public TelegramBotsApi telegramBotsApi(CommandsSessionBot bot) throws TelegramApiException {
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        telegramBotsApi.registerBot(bot);
        return telegramBotsApi;
    }
```
Insert:
```java
    @Bean
    @ConditionalOnMissingBean
    public TelegramClient telegramClient(CommandsSessionBotProperties properties) {
        return new OkHttpTelegramClient(properties.getToken());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public TelegramBotsLongPollingApplication telegramBotsApplication(
            CommandsSessionBot bot, CommandsSessionBotProperties properties) throws TelegramApiException {
        var application = new TelegramBotsLongPollingApplication();
        application.registerBot(properties.getToken(), bot);
        return application;
    }
```

Update the `bot(...)` bean to inject and pass the client. Replace:
```java
    @Bean
    public CommandsSessionBot bot(
            CommandsFactory commandsFactory,
            ErrorHandlerFactory errorHandler,
            AuthInterceptor authInterceptor,
            CommandsSessionBotProperties properties) {
        return new CommandsSessionBot(commandsFactory, authInterceptor, errorHandler, properties);
    }
```
with:
```java
    @Bean
    public CommandsSessionBot bot(
            CommandsFactory commandsFactory,
            ErrorHandlerFactory errorHandler,
            AuthInterceptor authInterceptor,
            CommandsSessionBotProperties properties,
            TelegramClient telegramClient) {
        return new CommandsSessionBot(commandsFactory, authInterceptor, errorHandler, properties, telegramClient);
    }
```

- [ ] **Step 4: Update `AbstractMessagePresenter.java`**

Add import:
```java
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
```
Change the `buildKeyboard` return type only (the `.keyboard(...)` calls accept the new type unchanged):
```java
    protected List<InlineKeyboardRow> buildKeyboard(S source, CommandContext context) {
        return null;
    }
```

- [ ] **Step 5: Update `TextParameterRenderer.java`**

Replace the whole file with:
```java
package com.kb.sessionbot.commands.dispatcher.parameters;

import com.kb.sessionbot.commands.CommandBuilder;
import org.reactivestreams.Publisher;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.ObjectUtils.isEmpty;
import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

public class TextParameterRenderer implements ParameterRenderer {
    @Override
    public Publisher<? extends PartialBotApiMethod<?>> render(ParameterRequest parameterRequest) {
        return Mono.fromSupplier(() -> {
            var messageBuilder = SendMessage.builder()
                .chatId(parameterRequest.getContext().getChatId())
                .text(parameterRequest.getText())
                .parseMode(ParseMode.HTML);

            List<InlineKeyboardRow> rowsInline = new ArrayList<>();
            if (!isEmpty(parameterRequest.getOptions())) {
                InlineKeyboardRow rowInline = parameterRequest.getOptions().stream()
                    .map(option ->
                        InlineKeyboardButton.builder()
                            .text(option.getValue())
                            .callbackData(option.getKey())
                            .build()
                    )
                    .collect(Collectors.toCollection(InlineKeyboardRow::new));

                rowsInline.add(rowInline);
            }
            if (!parameterRequest.isRequired()) {
                rowsInline.add(
                    new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("Пропусить")
                        .callbackData(CommandBuilder.create().scipAnswer(parameterRequest.getIndex()).build())
                        .build())
                );
            }
            if (isNotEmpty(rowsInline)) {
                messageBuilder.replyMarkup(InlineKeyboardMarkup.builder().keyboard(rowsInline).build());
            }
            return messageBuilder.build();
        });
    }
}
```

- [ ] **Step 6: Update `BooleanParameterRenderer.java`**

Replace the whole file with:
```java
package com.kb.sessionbot.commands.dispatcher.parameters;

import com.kb.sessionbot.commands.CommandBuilder;
import org.reactivestreams.Publisher;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

public class BooleanParameterRenderer implements ParameterRenderer {
    @Override
    public Publisher<? extends PartialBotApiMethod<?>> render(ParameterRequest parameterRequest) {
        return Mono.fromSupplier(() -> {
            List<InlineKeyboardRow> rowsInline = new ArrayList<>();
            InlineKeyboardRow rowInline = new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Да").callbackData(Boolean.toString(true)).build(),
                InlineKeyboardButton.builder().text("Нет").callbackData(Boolean.toString(false)).build()
            );

            rowsInline.add(rowInline);

            if (!parameterRequest.isRequired()) {
                rowsInline.add(
                    new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("Пропусить")
                        .callbackData(CommandBuilder.create().scipAnswer(parameterRequest.getIndex()).build())
                        .build())
                );
            }
            return SendMessage.builder()
                .chatId(parameterRequest.getContext().getChatId())
                .text(parameterRequest.getText())
                .parseMode(ParseMode.HTML)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rowsInline).build())
                .build();
        });
    }
}
```

- [ ] **Step 7: Update `DateParameterRenderer.java`**

Add import:
```java
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
```
(This file imports `java.util.*` via wildcard, so no `java.util` import edits are needed — `Arrays`/`Collections`/`ArrayList`/`List` remain covered.)

In `buildKeyBoard(...)`, change the collection type:
```java
        List<InlineKeyboardRow> rowsInline = new ArrayList<>();
```

Month-header row — replace `rowsInline.add(Collections.singletonList(...))` with:
```java
        rowsInline.add(new InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(date.format(DateTimeFormatter.ofPattern("MMMM yyyy")))
                .callbackData(CommandBuilder.create().addParam(DATE_PROPERTY, date.format(ISO_DATE)).addParam(CONTINUE_CHOOSE).build())
                .build()
        ));
```

Weekday-header row — change `rowsInline.add(Arrays.asList(` to `rowsInline.add(new InlineKeyboardRow(`
(the seven `InlineKeyboardButton.builder()...build()` arguments and the closing `));` stay the same):
```java
        rowsInline.add(new InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text("Пн")
                .callbackData(CommandBuilder.create().addParam(DATE_PROPERTY, date.format(ISO_DATE)).addParam(CONTINUE_CHOOSE).build())
                .build(),
            InlineKeyboardButton.builder()
                .text("Вт")
                .callbackData(CommandBuilder.create().addParam(DATE_PROPERTY, date.format(ISO_DATE)).addParam(CONTINUE_CHOOSE).build())
                .build(),
            InlineKeyboardButton.builder()
                .text("Ср")
                .callbackData(CommandBuilder.create().addParam(DATE_PROPERTY, date.format(ISO_DATE)).addParam(CONTINUE_CHOOSE).build())
                .build(),
            InlineKeyboardButton.builder()
                .text("Чт")
                .callbackData(CommandBuilder.create().addParam(DATE_PROPERTY, date.format(ISO_DATE)).addParam(CONTINUE_CHOOSE).build())
                .build(),
            InlineKeyboardButton.builder()
                .text("Пт")
                .callbackData(CommandBuilder.create().addParam(DATE_PROPERTY, date.format(ISO_DATE)).addParam(CONTINUE_CHOOSE).build())
                .build(),
            InlineKeyboardButton.builder()
                .text("Сб")
                .callbackData(CommandBuilder.create().addParam(DATE_PROPERTY, date.format(ISO_DATE)).addParam(CONTINUE_CHOOSE).build())
                .build(),
            InlineKeyboardButton.builder()
                .text("Вс")
                .callbackData(CommandBuilder.create().addParam(DATE_PROPERTY, date.format(ISO_DATE)).addParam(CONTINUE_CHOOSE).build())
                .build()
        ));
```

Week-row accumulator — change:
```java
            List<InlineKeyboardButton> weekRow = new ArrayList<>();
```
to:
```java
            InlineKeyboardRow weekRow = new InlineKeyboardRow();
```
(The `weekRow.add(...)` calls and `rowsInline.add(weekRow);` are unchanged.)

Month-navigation row — change `rowsInline.add(Arrays.asList(` to `rowsInline.add(new InlineKeyboardRow(`:
```java
        rowsInline.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("<")
                    .callbackData(
                        CommandBuilder.create()
                            .addParam(DATE_PROPERTY, date.minusMonths(1).format(ISO_DATE))
                            .addParam(CONTINUE_CHOOSE)
                            .build()
                    )
                    .build(),
                InlineKeyboardButton.builder()
                    .text(">")
                    .callbackData(
                        CommandBuilder.create()
                            .addParam(DATE_PROPERTY, date.plusMonths(1).format(ISO_DATE))
                            .addParam(CONTINUE_CHOOSE)
                            .build()
                    )
                    .build()
            )
        );
```

Skip row — replace `Collections.singletonList(...)` with:
```java
        if (!parameterRequest.isRequired()) {
            rowsInline.add(
                new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("Пропусить")
                    .callbackData(CommandBuilder.create().scipAnswer(parameterRequest.getIndex()).build())
                    .build())
            );
        }
```
(The final `return InlineKeyboardMarkup.builder().keyboard(rowsInline).build();` is unchanged.)

- [ ] **Step 8: Verify the whole project compiles**

Run: `mvn -q test-compile`
Expected: `BUILD SUCCESS`, no compilation errors. (If `getUserName()` or any other DTO
getter is flagged, fix the reference at the indicated line — DTOs are otherwise stable.)

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java
git commit -m "build: migrate to telegrambots 10.0.0 modular API

Replace the dead telegrambots 6.8.0 monolith with telegrambots-meta/client/longpolling
10.0.0. CommandsSessionBot becomes a LongPollingSingleThreadUpdateConsumer that sends via
an OkHttp TelegramClient; registration moves to TelegramBotsLongPollingApplication. Inline
keyboards migrate to InlineKeyboardRow. Removes the Apache-HttpClient/Jersey/Grizzly stack."
```

---

### Task 2: Update the auto-configuration test

**Files:**
- Modify: `src/test/java/com/kb/sessionbot/config/CommandsSessionBotAutoConfigurationTest.java`

- [ ] **Step 1: Replace the stubbed beans with the new types**

The old `TelegramBotsApi` bean no longer exists. Mock both new `@ConditionalOnMissingBean`
collaborators so the active context builds without registering a real bot or making the
startup `SetMyCommands` HTTP call. Replace the whole file with:
```java
package com.kb.sessionbot.config;

import com.kb.sessionbot.CommandsSessionBot;
import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.HelpCommand;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class CommandsSessionBotAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommandsSessionBotConfiguration.class));

    // Required properties present plus mocked client + long-polling app so the context
    // builds without real network: registerBot never runs and the startup SetMyCommands
    // call hits the mocked client.
    private final ApplicationContextRunner activeRunner = runner
            .withPropertyValues(
                    "sessionbot.telegram.token=test-token",
                    "sessionbot.telegram.bot-username=test-bot")
            .withBean(TelegramClient.class, () -> Mockito.mock(TelegramClient.class))
            .withBean(TelegramBotsLongPollingApplication.class,
                    () -> Mockito.mock(TelegramBotsLongPollingApplication.class));

    @Test
    void backsOffWhenTelegramPropertiesAbsent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CommandsSessionBot.class);
        });
    }

    @Test
    void activatesWhenTelegramPropertiesPresent() {
        activeRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommandsSessionBot.class);
            assertThat(context).hasSingleBean(HelpCommand.class);
            assertThat(context).hasSingleBean(AuthInterceptor.class);
        });
    }

    @Test
    void allowsDownstreamToOverrideConditionalBeans() {
        AuthInterceptor custom = request -> Mono.just(false);
        activeRunner.withBean(AuthInterceptor.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuthInterceptor.class);
            assertThat(context.getBean(AuthInterceptor.class)).isSameAs(custom);
        });
    }
}
```

- [ ] **Step 2: Verify test compilation**

Run: `mvn -q test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java
git commit -m "test: update auto-config test for telegrambots 10.0.0 beans"
```

---

### Task 3: Update `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the stack line**

Change:
```
Java 21, Spring Boot 3.5, Maven, Project Reactor, Lombok, `org.telegram:telegrambots` 6.8.0.
```
to:
```
Java 21, Spring Boot 3.5, Maven, Project Reactor, Lombok, `org.telegram:telegrambots-{meta,client,longpolling}` 10.0.0.
```

- [ ] **Step 2: Update the processing-flow description**

Change the line describing the bot class:
```
`CommandsSessionBot` (extends `TelegramLongPollingBot`) is fully reactive — it does **not** process updates inline:
```
to:
```
`CommandsSessionBot` (a `LongPollingSingleThreadUpdateConsumer`, registered via `TelegramBotsLongPollingApplication` and sending through an OkHttp `TelegramClient`) is fully reactive — it does **not** process updates inline:
```
And in step 1 of that numbered list, change `onUpdateReceived` to `consume`:
```
1. `consume` pushes every `Update` into a Reactor `Sinks.Many`.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for telegrambots 10.0.0 architecture"
```

---

### Task 4: Final verification

**Files:** none (verification only)

- [ ] **Step 1: Confirm the CVE deps are resolved**

Run: `mvn dependency:tree`
Expected: no `org.glassfish.grizzly:grizzly-http` anywhere; `commons-io:commons-io` (if
present, via `telegrambots-client`) resolves to `>= 2.14.0`; `com.squareup.okhttp3:okhttp`
present. Confirm `org.telegram:telegrambots-{meta,client,longpolling}:10.0.0`.

- [ ] **Step 2: Owner runs the test suite**

Hand off to the repo owner: `mvn test`
Expected: `BUILD SUCCESS`, all three auto-config tests pass.

- [ ] **Step 3 (optional): Live smoke test**

With a real bot token in `sessionbot.telegram.token`, start a downstream app and confirm
long-polling receives updates and a command replies (validates the `TelegramClient` send +
`TelegramBotsLongPollingApplication` polling end-to-end).

- [ ] **Step 4 (optional): Re-run the dependency CVE scan**

Confirm no new advisories from the added OkHttp / Kotlin-stdlib transitives.