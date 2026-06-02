# Design: Upgrade telegrambots 6.8.0 → 10.0.0

**Date:** 2026-06-02
**Branch:** `sb3-upgrade` (or a follow-on branch)
**Status:** Approved design, ready for implementation plan

## Goal

Migrate the library off the dead `org.telegram:telegrambots` 6.x monolith (last
release 6.9.7.1) onto the modular 10.0.0 architecture. This resolves the two
remaining transitive CVEs deferred earlier:

- `org.glassfish.grizzly:grizzly-http:4.0.2` — CVE-2024-45687 (removed entirely;
  the new long-polling stack has no Jersey/Grizzly server)
- `commons-io:commons-io:2.11.0` — CVE-2024-47554 (resolves to a patched version
  via `telegrambots-client`)

The HTTP transport moves from Apache HttpClient/Jersey to **OkHttp**. Kotlin
stdlib is pulled in transitively by `telegrambots-longpolling`.

## Scope

- **In scope:** long-polling bots only; dependency swap; refactor of
  `CommandsSessionBot`, the auto-config, and the four inline-keyboard builders;
  test + docs updates.
- **Out of scope:** webhook support; any change to the reactive pipeline, command
  dispatch, parameter rendering model, auth, or wire-format/`MessageDescriptor`.
- **Compatibility:** breaking major change — public types are updated to the new
  telegrambots APIs directly, no compatibility shims.

## Background: what changed in telegrambots 7.0+

Starting at 7.0.0 the library split into separate artifacts and dropped the
`org.telegram.telegrambots.bots.*` base classes:

- Update consumption: implement `LongPollingSingleThreadUpdateConsumer`
  (`void consume(Update)`), registered via `TelegramBotsLongPollingApplication`.
- Sending: the `TelegramClient` interface (impl `OkHttpTelegramClient`), no longer
  a method on the bot.
- Inline keyboards: `InlineKeyboardMarkup` now takes `List<InlineKeyboardRow>`
  instead of `List<List<InlineKeyboardButton>>`. `InlineKeyboardRow extends
  ArrayList<InlineKeyboardButton>`.

Verified API signatures (v10.0.0 source):
- `interface LongPollingSingleThreadUpdateConsumer extends LongPollingUpdateConsumer { void consume(Update update); }`
- `class TelegramBotsLongPollingApplication implements AutoCloseable { BotSession registerBot(String botToken, LongPollingUpdateConsumer consumer); void close(); }`
- `interface TelegramClient { <T extends Serializable, M extends BotApiMethod<T>> T execute(M method); ... }`
- `OkHttpTelegramClient(String botToken)`
- `class InlineKeyboardRow extends ArrayList<InlineKeyboardButton>`

## Changes

### 1. Dependencies — `pom.xml`

Remove:
- `org.telegram:telegrambots:${telegram-bot.version}`
- `org.telegram:telegrambots-meta:${telegram-bot.version}`
- `org.telegram:telegrambotsextensions:${telegram-bot.version}` (no imports anywhere — unused)
- the `jackson-module-jaxb-annotations` exclusion on the old `telegrambots` dep
  (obsolete — the new stack has no Jersey)

Add (bump `telegram-bot.version` to `10.0.0`):
- `org.telegram:telegrambots-meta:10.0.0`
- `org.telegram:telegrambots-client:10.0.0`
- `org.telegram:telegrambots-longpolling:10.0.0`

### 2. `CommandsSessionBot` — `src/main/java/com/kb/sessionbot/CommandsSessionBot.java`

- Change `extends TelegramLongPollingBot` → `implements LongPollingSingleThreadUpdateConsumer`.
- Add a `private final TelegramClient telegramClient;` field, supplied via the
  constructor (new last parameter).
- Replace `onUpdateReceived(Update)` with `@Override public void consume(Update update)`
  (same body: `updatesSink.tryEmitNext(update)`).
- Remove the `getBotToken()` and `getBotUsername()` overrides (no longer an
  interface contract; token now lives on the client and the registration call).
- In `executeMessage(...)`, keep the exact structure but call
  `telegramClient.execute((BotApiMethod<T>) message)` instead of the inherited
  `execute(...)`.
- `@PostConstruct init()`, the sinks, `handleUpdates`, auth, dispatch, and the
  `SetMyCommands` startup flow are unchanged.

Drop the now-unused import `org.telegram.telegrambots.bots.TelegramLongPollingBot`;
add `org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer`
and `org.telegram.telegrambots.meta.generics.TelegramClient`.

### 3. Auto-config — `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java`

Replace the `telegramBotsApi(CommandsSessionBot)` bean with:

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

- The existing `bot(...)` bean gains a `TelegramClient` parameter, passed to the
  `CommandsSessionBot` constructor.
- `destroyMethod = "close"` ensures the polling session is stopped on context
  shutdown (the old code never unregistered — this is a net improvement).
- Bean ordering is safe: `telegramBotsApplication` depends on `bot`, whose
  `@PostConstruct init()` subscribes the pipeline before registration starts
  pushing updates.

### 4. Inline keyboards — four files

Uniform type swap: `List<List<InlineKeyboardButton>>` → `List<InlineKeyboardRow>`,
and each inner `List<InlineKeyboardButton>` row → `InlineKeyboardRow`.
`InlineKeyboardMarkup.builder().keyboard(...)` now receives `List<InlineKeyboardRow>`.

- `commands/presenter/AbstractMessagePresenter.java` — `buildKeyboard(...)` return
  type becomes `List<InlineKeyboardRow>` (lines ~26, 46, 55, 66).
- `commands/dispatcher/parameters/TextParameterRenderer.java` — `rowsInline` and
  `rowInline` (lines ~30, 32, 52).
- `commands/dispatcher/parameters/BooleanParameterRenderer.java` — `rowsInline` and
  `rowInline` (lines ~25, 26, 45).
- `commands/dispatcher/parameters/DateParameterRenderer.java` — `rowsInline`,
  `weekRow`, and the `.builder()` chain (lines ~51, 98, 155).

Since `InlineKeyboardRow` is an `ArrayList<InlineKeyboardButton>`, existing
`.stream()`/`Arrays.asList(...)`/`.add(...)` usage adapts by constructing an
`InlineKeyboardRow` (it has a `Collection` constructor) instead of an `ArrayList`.

### 5. Properties & DTOs

- `bot-username` stays **required** in `@ConditionalOnProperty(value = {"token",
  "bot-username"})` — activation contract unchanged. `CommandsSessionBotProperties.
  getBotUsername()` is retained as informational (registration uses the token only).
- Fix any incidental compile drift the compiler surfaces (e.g. `ParseMode` import
  location); the DTOs in use (`SendMessage`, `EditMessageText`,
  `EditMessageReplyMarkup`, `SetMyCommands`, `BotCommand`, `Update`, `Message`,
  `CallbackQuery`, `User`) are otherwise stable.

### 6. Tests — `src/test/java/com/kb/sessionbot/config/CommandsSessionBotAutoConfigurationTest.java`

- The `withBean(TelegramBotsApi.class, mock)` stub is removed (the bean no longer
  exists).
- `activeRunner` instead supplies a mocked `TelegramBotsLongPollingApplication`
  (now `@ConditionalOnMissingBean`) so the auto-config's registering bean backs
  off and no real polling starts. A real or mocked `TelegramClient` may also be
  supplied; `OkHttpTelegramClient(token)` construction does no network, so the
  default bean is acceptable.
- `backsOffWhenTelegramPropertiesAbsent` is unchanged.
- `activatesWhenTelegramPropertiesPresent` and
  `allowsDownstreamToOverrideConditionalBeans` keep asserting on
  `CommandsSessionBot`/`HelpCommand`/`AuthInterceptor`.

### 7. Docs — `CLAUDE.md`

- Update the stack line: "`org.telegram:telegrambots` 6.8.0" → the modular 10.0.0
  artifacts (`telegrambots-meta`, `telegrambots-client`, `telegrambots-longpolling`).
- Update "`CommandsSessionBot` (extends `TelegramLongPollingBot`)" to describe the
  `LongPollingSingleThreadUpdateConsumer` + `TelegramClient` model and registration
  via `TelegramBotsLongPollingApplication`.

## Verification

- `mvn dependency:tree` — confirm `grizzly-http` is absent and `commons-io`
  resolves to ≥ 2.14.0.
- `mvn test` — all green (back-off + activation + override tests).
- Optional manual smoke test against a real bot token to confirm long-polling and
  message send still work end-to-end.

## Risks

- **Behavioral:** `TelegramBotsLongPollingApplication` polling/threading differs
  from the old `DefaultBotSession`; the optional live smoke test covers this.
- **Transitive surface:** OkHttp + Kotlin stdlib are new; both are actively
  maintained. Re-run the CVE scan after the bump to confirm no new advisories.
- **Inline-keyboard swap** touches four files; the uniform pattern keeps it low
  risk, verified by compilation and existing rendering behavior.