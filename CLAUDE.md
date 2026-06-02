# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A **Spring Boot auto-configuration starter** (`com.kb:telegram-session-bot`) for building Telegram bots whose commands behave like multi-step conversations. A command can ask the user for missing arguments one at a time (via inline keyboards or text replies); the framework accumulates those answers into a per-chat session until the command has everything it needs to run. Published as a Maven artifact to a GitHub-hosted repo (`baevkir/library-project`, `mvn-repo` branch).

Java 21, Spring Boot 3.5, Maven, Project Reactor, Lombok, `org.telegram:telegrambots-{meta,client,longpolling}` 10.0.0.

## Build & test

```bash
mvn clean install                                   # build + install to local repo
mvn package                                          # build jar
mvn test                                             # all tests
mvn test -Dtest=MessageDescriptorTest               # single test class
mvn test -Dtest=MessageDescriptorTest#parseCommandOnlyCommand   # single method
```

Note: `.gitignore` references Gradle, but this project builds with Maven (`pom.xml`). There are no application classes here — it's a library; downstream apps supply the `@BotCommand` beans and config.

## How it wires up (auto-configuration)

`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` → `CommandsSessionBotConfiguration` (an `@AutoConfiguration` class, registered via the Spring Boot 3 imports mechanism), which is `@ConditionalOnProperty(prefix = "sessionbot.telegram", value = {"token", "bot-username"})`. A consuming app activates the bot purely by setting:

```
sessionbot.telegram.token=...
sessionbot.telegram.bot-username=...
```

The config scans the app context for beans annotated `@BotCommand` and wraps each in a `DispatcherBotCommand`. Most beans (`HelpCommand`, `CommandsFactory`, error handlers, parameter renderers, `AuthInterceptor`) are `@ConditionalOnMissingBean`, so downstream apps override by simply declaring their own.

## Core processing flow

`CommandsSessionBot` (a `LongPollingSingleThreadUpdateConsumer`, registered via `TelegramBotsLongPollingApplication` and sending through an OkHttp `TelegramClient`) is fully reactive — it does **not** process updates inline:

1. `consume` pushes every `Update` into a Reactor `Sinks.Many`.
2. Updates are `groupBy(chatId)`, so each chat is an independent sequential stream.
3. `handleUpdates` uses `scanWith(CommandContext::empty, ...)` to fold a chat's update stream into an evolving **`CommandContext`**:
   - a command update (`/foo`) starts a fresh context,
   - a non-command update appends an answer to the current context,
   - the `refreshContext` dynamic param rebuilds the context from the original command.
4. The matched `IBotCommand.process(context)` returns a `Publisher<BotCommandResult>`; each result's `PartialBotApiMethod` is executed against the Telegram API.

`CommandContext` is the session state, holding the originating command update, accumulated `answers`, sent `questionMessages`, and a `ContextState` (`open → progress → close`). When a command needs more input it enters `progress`; when complete it `close`s and the framework deletes the prior question/answer messages (`DeleteMessage`) to keep the chat clean.

## Command dispatch & argument matching

A command class:

```java
@BotCommand(value = "order", description = "...", hidden = false)
public class OrderCommand {
    @CommandMethod(arguments = "buy&{product}")
    public Mono<BotCommandResult> buy(@Parameter("product") String product) { ... }
}
```

- `CommandsDispatcher` reflects over `@CommandMethod` methods. `MethodMatcher` scores each method's `arguments` template against the context's accumulated answers; literal segments must match, `{placeholder}` segments bind by name. Highest score wins (see `MethodMatcher.getMatchingScore`).
- For each `@Parameter` the dispatcher either pulls the bound answer (JSON-converted via Jackson to the parameter type) or, if missing and required, calls a **ParameterRenderer** to prompt the user and suspends the command in `progress` state.
- Method params can also be auto-injected by type+name without `@Parameter`: `UpdateWrapper command`/`update`, `Update update`, `User from`, `String chatId`, `DynamicParameters`, `CommandContext`.
- Method return values are normalized by `InvocationResultResolver` (supports `BotCommandResult`, raw api methods, `Publisher`, etc.).

## Wire format (callback data) — `MessageDescriptor` / `CommandBuilder`

Commands and inline-button callbacks are encoded as strings, parsed by `MessageDescriptor` and built by `CommandBuilder` (constants in `CommandConstants`):

```
/command?answer1&answer2#dynParam1:value&dynParam2
```

- `/` command prefix, `?` separates command from answers, `&` separates answers, `#` introduces dynamic params, `:` is key/value.
- A message **without** a leading `/` is treated as answers/dynamic-params for the in-progress context (i.e. a button press or text reply).
- **64-byte limit**: Telegram caps callback data at 64 bytes; `CommandBuilder.build()` warns when exceeded. Keep command/answer strings short.

**Dynamic params** (control flags, set via `CommandBuilder`, read via `DynamicParameters`): `refreshContext` (rebuild context), `scipAnswer:<index>` (allow skipping optional answers up to index), `approved`, `initiator:<name>`.

## Parameter renderers (prompting for input)

`ParameterRenderer.render(ParameterRequest) → Publisher<BotCommandResult>` produces the message that asks the user for a value. `ParameterRendererFactory` resolves which renderer to use per parameter, with a parent/child hierarchy: the global factory holds shared renderer beans; each command gets a child factory (`createChild`) that also exposes that command's own `@RenderingMethod`-annotated methods.

Built-ins: `TextParameterRenderer`, `DateParameterRenderer`, `BooleanParameterRenderer`, and `CompositeParameterRenderer` (the `defaultParameterRenderer`, which picks among them). Select a renderer on a parameter via `@Parameter(rendering = @Rendering(name = "...", type = ..., options = {...}))`. A command defines a custom one with `@RenderingMethod("name")` on a method taking `ParameterRequest`. `AbstractMessagePresenter` is a helper base for emitting `SendMessage`/`EditMessageText`/`EditMessageReplyMarkup` with inline keyboards.

## Auth & errors

- `AuthInterceptor.intercept(context) → Mono<Boolean>` gates every command (default bean allows all). Return `false` → `BotAuthException`.
- `ErrorHandlerFactory` dispatches thrown errors to `ErrorHandler` beans by exception type; `BotCommandErrorHandler` and `BotAuthErrorHandler` are the defaults. Domain exceptions: `BotCommandException`, `BotAuthException` (both carry the `CommandContext`).