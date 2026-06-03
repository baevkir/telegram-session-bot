# Configurable Label Language (i18n) Design Spec

> Make all built-in user-facing labels (HelpCommand, parameter renderers, dispatcher prompts/errors)
> resolvable from a configured language, shipping `en`/`uk`/`ru` bundles, selectable via app config
> and fully overridable. Bot-wide by default (every user sees the configured language); per-user is a
> drop-in `LocaleProvider` override, never tied to the Telegram client locale.

## Goal

Replace the hardcoded built-in strings with a small, overridable i18n layer:

1. **One configured language for the whole bot** — `sessionbot.telegram.language=uk` makes every user
   see Ukrainian. Default is `en`.
2. **Shipped languages** — the library ships `en` (default), `uk`, and `ru` bundles for all built-in
   labels.
3. **Extensible** — a consumer can override a single label, add a language, switch language, or do
   per-user language, all without forking the library.
4. **Not tied to the Telegram locale** — language is decided by the app/consumer, not the user's
   Telegram `language_code`.

This also subsumes the earlier "translate hardcoded Russian labels to English" task: English becomes
the default bundle's content.

## Background

Built-in user-facing strings are currently hardcoded (in Russian) across:

| File | Strings |
|---|---|
| `commands/HelpCommand.java` | command description, title, intro line |
| `commands/dispatcher/parameters/TextParameterRenderer.java` | "Skip" |
| `commands/dispatcher/parameters/BooleanParameterRenderer.java` | "Yes" / "No" / "Skip" |
| `commands/dispatcher/parameters/DateParameterRenderer.java` | weekday abbreviations, date-format hint, "Skip" |
| `commands/dispatcher/CommandsDispatcher.java` | missing-parameter prompt, unsupported-options error |

Consumer-authored annotation text (`@BotCommand.description`, `@Parameter.displayName`,
`@RenderingOption.displayValue`) is also made translatable via a `{key}` convention — see
"Annotation label resolution" below.

## Architecture

Three small, independently overridable units (each `@ConditionalOnMissingBean`):

```
   render site (HelpCommand / renderers / CommandsDispatcher)
            │ botLabels.helpTitle(ctx) / .skip(ctx) / .missingParameter(ctx, field) …
            ▼
        BotLabels (facade)
            │  userName = ctx.getCommandUpdate().getFrom()?.getUserName()
            │  Locale  = localeProvider.getLocale(userName)
            ▼                                   ▼
   LocaleProvider (who→language)        MessageSource (language→text)
   default: ConfiguredLocaleProvider     sessionbotLabelsMessageSource
   (returns configured locale,           (ResourceBundle: sessionbot-labels[_uk|_ru],
    ignores userName)                      + sessionbot-labels-override checked first)
```

- `LocaleProvider` decides **which** language for a given user name.
- `MessageSource` holds the **text** per language.
- `BotLabels` is the only thing call sites touch; it wires the two together so no method needs a
  `Locale` parameter (the `CommandContext` carries the user).

## Components

### `LocaleProvider` (interface, new — SPI)

```java
package com.kb.sessionbot.i18n;

import java.util.Locale;

/** Resolves the bot's language for a given user. */
public interface LocaleProvider {
    /**
     * @param userName the Telegram user name (from {@code update.getFrom().getUserName()}); may be
     *                 null when the update has no sender. Implementations must tolerate null.
     */
    Locale getLocale(String userName);
}
```

### `ConfiguredLocaleProvider` (default impl, new)

```java
public class ConfiguredLocaleProvider implements LocaleProvider {
    private final Locale locale;
    public ConfiguredLocaleProvider(Locale locale) { this.locale = locale; }
    @Override public Locale getLocale(String userName) { return locale; } // bot-wide: ignores userName
}
```

Constructed from `sessionbot.telegram.language` (default `en`). A consumer overrides the
`LocaleProvider` bean to return a per-user locale (e.g. looked up by `userName`).

### `sessionbotLabelsMessageSource` (bean, new — the label store)

A dedicated, **named** `MessageSource` (not the primary one — must not clobber the app's
auto-configured `messageSource`):

```java
var ms = new ResourceBundleMessageSource();
ms.setBasenames("sessionbot-labels-override", "sessionbot-labels"); // override checked first
ms.setDefaultEncoding("UTF-8");          // required for Cyrillic
ms.setFallbackToSystemLocale(false);     // unknown locale -> default bundle, not the host's locale
ms.setUseCodeAsDefaultMessage(false);
// Fall through to the app's own MessageSource for consumer-authored {key} annotation text:
appMessageSource.ifAvailable(ms::setParentMessageSource);  // ObjectProvider for the bean named "messageSource"
```

Bean name `sessionbotLabelsMessageSource`, `@ConditionalOnMissingBean(name = "sessionbotLabelsMessageSource")`.
`BotLabels` injects it by name/qualifier so the app's general `MessageSource` is untouched. The
**parent** chain means library label keys resolve from the library bundles, while consumer `{key}`
annotation text falls through to the app's standard `messages[_uk|_ru].properties` — both localized by
the same `Locale`. The parent is obtained via `ObjectProvider<MessageSource>` qualified to the
Spring-Boot bean named `messageSource`; if absent, no parent is set.

### `BotLabels` (facade, new)

Depends on the `MessageSource` interface (never the concrete impl) + `LocaleProvider`. Typed
accessors keep keys and locale resolution in one place:

```java
public class BotLabels {
    private final MessageSource messages;       // sessionbotLabelsMessageSource
    private final LocaleProvider localeProvider;

    public String helpTitle(CommandContext ctx)                 { return get("help.title", ctx); }
    public String helpIntro(CommandContext ctx)                 { return get("help.intro", ctx); }
    public String helpDescription()                             { return get("help.description", null); }
    public String skip(CommandContext ctx)                      { return get("button.skip", ctx); }
    public String yes(CommandContext ctx)                       { return get("button.yes", ctx); }
    public String no(CommandContext ctx)                        { return get("button.no", ctx); }
    public String missingParameter(CommandContext ctx, String field)
                       { return get("param.missing", ctx, field); }
    public String unsupportedOptions(CommandContext ctx, Object options, Object command)
                       { return get("command.unsupportedOptions", ctx, options, command); }
    public String dateFormatHint(CommandContext ctx, String label, String format)
                       { return get("date.formatHint", ctx, label, format); }
    public String weekday(CommandContext ctx, DayOfWeek day)    { return get("weekday." + day.name().toLowerCase().substring(0,3), ctx); }

    /**
     * Resolve consumer-authored annotation text. If {@code text} is wholly wrapped in braces it is
     * treated as a message code with an optional inline default: {@code {code}} or
     * {@code {code:default text}}. The code is resolved; if unknown, the inline default is used (or,
     * if no inline default was given, the original literal text). Anything not wholly brace-wrapped
     * is returned unchanged. Resolution never throws.
     */
    public String resolve(String text, CommandContext ctx) { return resolve(text, userName(ctx)); }

    /** Resolve by user name directly — for out-of-band (outbound) messages with no incoming context.
     *  Pass {@code null} userName for the configured/bot-wide locale. */
    public String resolve(String text, String userName) {
        if (text == null) return null;
        var trimmed = text.trim();
        if (trimmed.length() > 2 && trimmed.startsWith("{") && trimmed.endsWith("}")
                && trimmed.indexOf('}') == trimmed.length() - 1) {
            var inner = trimmed.substring(1, trimmed.length() - 1);
            int sep = inner.indexOf(':');                       // split on FIRST ':' -> code : default
            var code = (sep >= 0 ? inner.substring(0, sep) : inner).trim();
            var fallback = sep >= 0 ? inner.substring(sep + 1) : text;   // inline default, else literal
            return messages.getMessage(code, null, fallback, localeProvider.getLocale(userName));
        }
        return text;
    }

    private String get(String key, CommandContext ctx, Object... args) {
        return messages.getMessage(key, args, localeProvider.getLocale(userName(ctx)));
    }

    private String userName(CommandContext ctx) {
        return ctx == null ? null
            : Optional.ofNullable(ctx.getCommandUpdate()).map(UpdateWrapper::getFrom)
                      .map(User::getUserName).orElse(null);
    }
}
```

`helpDescription()` has no `CommandContext` (it backs `IBotCommand.getDescription()`, used by the
startup `SetMyCommands` and the help listing), so it resolves at the configured/default locale
(`getLocale(null)`). For the bot-wide default this is identical to everything else; with a per-user
`LocaleProvider`, command descriptions remain at the configured locale (documented limitation —
`getDescription()` has no user to key on).

## Message keys & translations (all three bundles ship complete)

All three bundles ship complete, human-authored translations for every key. `args` shows the
`MessageFormat` arguments.

| Key | `en` (default) | `uk` | `ru` | args |
|---|---|---|---|---|
| `help.title` | `Help` | `Довідка` | `Помощь` | — |
| `help.intro` | `The following commands are registered for the bot:` | `Для бота зареєстровані такі команди:` | `Следующие команды зарегистрированы для бота:` | — |
| `help.description` | `Get the list of available commands.` | `Отримати список доступних команд.` | `Получить список доступных команд.` | — |
| `button.skip` | `Skip` | `Пропустити` | `Пропустить` | — |
| `button.yes` | `Yes` | `Так` | `Да` | — |
| `button.no` | `No` | `Ні` | `Нет` | — |
| `param.missing` | `Please provide the field ''{0}''.` | `Будь ласка, вкажіть поле ''{0}''.` | `Пожалуйста, укажите поле ''{0}''.` | {0}=field |
| `command.unsupportedOptions` | `Options {0} are not supported for command {1}` | `Опції {0} не підтримуються для команди {1}` | `Опции {0} не поддерживаются для команды {1}` | {0},{1} |
| `date.formatHint` | `{0} (Format: {1})` | `{0} (Формат: {1})` | `{0} (Формат: {1})` | {0}=label,{1}=pattern |
| `weekday.mon` | `Mon` | `Пн` | `Пн` | — |
| `weekday.tue` | `Tue` | `Вт` | `Вт` | — |
| `weekday.wed` | `Wed` | `Ср` | `Ср` | — |
| `weekday.thu` | `Thu` | `Чт` | `Чт` | — |
| `weekday.fri` | `Fri` | `Пт` | `Пт` | — |
| `weekday.sat` | `Sat` | `Сб` | `Сб` | — |
| `weekday.sun` | `Sun` | `Нд` | `Вс` | — |

Notes:
- `MessageSource` uses `java.text.MessageFormat` (`{0}` placeholders), replacing the current
  `String.format("%s")` — and a literal `'` must be doubled (`''`), as shown in `param.missing`.
- `.properties` files are ISO-8859-1 by default, so the Cyrillic `uk`/`ru` values must be written as
  Unicode escapes (`\uXXXX`) **or** the bundle loaded as UTF-8. We set
  `ResourceBundleMessageSource.setDefaultEncoding("UTF-8")` and author the files in UTF-8, so the
  literal Cyrillic above is stored as-is (no `\u` escaping needed).

## Annotation label resolution (`{key}` convention)

Consumer-authored text on three annotation fields becomes translatable: if the **whole** value is
`{code}` (or `{code:default}`), it is resolved as a message code (via `BotLabels.resolve`); otherwise
it is used literally. Partial braces (e.g. `"Order {0}"`) stay literal. Resolution is lenient — an
unknown code falls back to the inline default if given, else to the original text; it never throws.

```java
@BotCommand(value = "order", description = "{order.description}")                  // resolve; fallback = literal "{order.description}"
@BotCommand(value = "order", description = "{order.description:Place an order}")    // resolve; fallback = "Place an order"
@BotCommand(value = "ping",  description = "Health check")                          // literal
```

The inline default (everything after the first `:`) mirrors Spring's `${key:default}` placeholder
convention, so an unresolved key shows a sensible string instead of the raw `{key}`.

| Annotation field | Read at | Resolve call site | Locale |
|---|---|---|---|
| `@BotCommand.description` | `DispatcherBotCommand.getDescription(String userName)` (feeds startup `SetMyCommands` with `null`, and the help listing with the recipient's user name) | `getDescription(userName)` returns `botLabels.resolve(rawDescription, userName)` | per-user-capable (configured when `userName` is null) |
| `@Parameter.displayName` | missing-parameter prompt (`CommandsDispatcher`, ctx present) | wrap with `botLabels.resolve(parameter.getDisplayName(), context)` before formatting `param.missing` | per-user-capable |
| `@RenderingOption.displayValue` | option buttons (renderers, `ParameterRequest.getContext()` present) | resolve each option's display value via `botLabels.resolve(option.getDisplayValue(), ctx)` when building buttons | per-user-capable |

`IBotCommand` exposes a single `String getDescription(String userName)` (no no-arg variant): the
startup `SetMyCommands` passes `null` (configured locale), and `HelpCommand`'s listing passes the
recipient's user name (per-user-capable). Keying on `userName` (rather than `CommandContext`) keeps
`IBotCommand` decoupled from the context type and aligns with `LocaleProvider.getLocale(String)`.

Consumer keys live in the **app's own** message bundles (`messages_uk.properties`, …), reached through
the parent `MessageSource` chain — so consumers write annotation translations in standard Spring
messages files, no library-specific bundle required. `ParameterDescriptor` keeps carrying the raw
annotation strings unchanged; resolution happens at render time where the `CommandContext` (hence the
locale) is known.

`HelpCommand`'s own `getDescription()` likewise returns `botLabels.resolve("{help.description}"-equivalent)`
— but since the library ships `help.description` in its bundles, it resolves there directly via the
typed `helpDescription()` accessor (no `{}` needed for library-owned labels).

## Configuration

| Property | Type | Default | Meaning |
|---|---|---|---|
| `sessionbot.telegram.language` | `String` | `en` | BCP-47 / ISO language tag; resolved to a `Locale` (`Locale.forLanguageTag`/`Locale.of`). |

Ukrainian is `uk` (not `ua`). Added to `CommandsSessionBotProperties`.

## Wiring (auto-configuration)

New `@Bean`s in `CommandsSessionBotConfiguration`, all `@ConditionalOnMissingBean`:
- `sessionbotLabelsMessageSource(ObjectProvider<MessageSource> appMessageSource)` → the
  `ResourceBundleMessageSource` above, with the app's `messageSource` set as parent (if present).
- `localeProvider(CommandsSessionBotProperties)` → `new ConfiguredLocaleProvider(Locale.forLanguageTag(properties.getLanguage()))`.
- `botLabels(@Qualifier("sessionbotLabelsMessageSource") MessageSource, LocaleProvider)` → `new BotLabels(...)`.

`BotLabels` is injected into the existing beans/classes that hold or resolve labels:
- `helpCommand(...)` bean → `HelpCommand` gains a `BotLabels` constructor arg (title/intro/description).
- `textParameterRenderer()`, `booleanParameterRenderer()`, `dateParameterRenderer()` → each gains a
  `BotLabels` constructor arg (button labels, weekday/date hints, and `{key}` option `displayValue`).
- `CommandsDispatcher` is created via `new CommandsDispatcher(command, applicationContext)` inside
  `DispatcherBotCommand`; it already holds the `ApplicationContext`, so it obtains `BotLabels` via
  `applicationContext.getBean(BotLabels.class)` (no constructor-signature change). Used for the
  `param.missing` prompt and for resolving `@Parameter.displayName`.
- `DispatcherBotCommand` likewise obtains `BotLabels` from the `ApplicationContext` so its
  `getDescription()` returns `botLabels.resolve(rawDescription, null)` (resolving `{key}` command
  descriptions at the configured locale).

## Extension points (consumer)

- **Override one label:** drop `sessionbot-labels-override[_uk].properties` on the classpath with just
  the changed keys — checked before the library bundle.
- **Add a language:** drop `sessionbot-labels_fr.properties` and set `sessionbot.telegram.language=fr`.
- **Per-user language:** provide a `LocaleProvider` bean that maps `userName → Locale`.
- **Full control:** provide a `sessionbotLabelsMessageSource` or `botLabels` bean (any `MessageSource`
  impl — DB-backed, remote, subclass).
- **Outbound messages:** `BotLabels` is a public bean; when building an out-of-band message for
  `OutboundMessageBus`, resolve label text with `botLabels.resolve("{key}", null)` (configured locale)
  or `botLabels.resolve("{key}", userName)` (that recipient's language). No framework interception of
  outbound bodies — the producer resolves explicitly, so the locale is unambiguous.

## Accepted decisions / limitations

- Bot-wide by default; per-user only if the consumer overrides `LocaleProvider`. No dependence on the
  Telegram `language_code`.
- `IBotCommand.getDescription()` (no user context) resolves at the configured locale even under a
  per-user `LocaleProvider`. Acceptable: it feeds the bot-wide `SetMyCommands`.
- The label `MessageSource` is a dedicated named bean, deliberately separate from the app's primary
  `MessageSource`, so the two never interfere.
- All three bundles (`en`, `uk`, `ru`) ship with **complete, human-authored translations** for every
  key (see the translations table) — selecting any of the three yields fully localized output, no
  missing keys, no `en` fallback.

## Testing

- **`BotLabelsTest`** — with a `ConfiguredLocaleProvider(en)` and the real bundles: `helpTitle` →
  `Help`; switching the provider to `uk` → Ukrainian value; `missingParameter("product")` formats the
  arg; a `sessionbot-labels-override.properties` on the test classpath wins over the default.
- **`ConfiguredLocaleProviderTest`** — returns the configured locale regardless of `userName` (incl.
  null).
- **Locale-override behavior** — a custom `LocaleProvider` returning `uk` for a specific user name and
  `en` otherwise changes `BotLabels` output accordingly.
- **`BotLabels.resolve` (annotation `{key}`)** — `"{some.key}"` resolves from a (test) parent
  `MessageSource`; `"{missing.key}"` falls back to the literal `"{missing.key}"`;
  `"{missing.key:Fallback}"` falls back to `"Fallback"`; `"Health check"` and `"Order {0} items"`
  (partial braces) pass through unchanged.
- **Existing tests updated** — `HelpCommand`/renderer/dispatcher tests assert against `BotLabels`
  defaults (English) instead of the old literals; `TelegramUpdateHandlerTest.emptyContextUsesHelp`
  asserts `contains("Help")`.
- Reuse existing fixtures; tests run under Java 21.

## File structure

| File | Action |
|------|--------|
| `src/main/java/com/kb/sessionbot/i18n/LocaleProvider.java` | Create |
| `src/main/java/com/kb/sessionbot/i18n/ConfiguredLocaleProvider.java` | Create |
| `src/main/java/com/kb/sessionbot/i18n/BotLabels.java` | Create |
| `src/main/resources/sessionbot-labels.properties` | Create (en default) |
| `src/main/resources/sessionbot-labels_uk.properties` | Create |
| `src/main/resources/sessionbot-labels_ru.properties` | Create |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotProperties.java` | Modify (`language`) |
| `src/main/java/com/kb/sessionbot/config/CommandsSessionBotConfiguration.java` | Modify (3 beans + inject into renderer/help beans) |
| `src/main/java/com/kb/sessionbot/commands/HelpCommand.java` | Modify (use `BotLabels`) |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/TextParameterRenderer.java` | Modify |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/BooleanParameterRenderer.java` | Modify |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/parameters/DateParameterRenderer.java` | Modify |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/CommandsDispatcher.java` | Modify (use `BotLabels` via ApplicationContext: `param.missing` + resolve `@Parameter.displayName`) |
| `src/main/java/com/kb/sessionbot/commands/dispatcher/DispatcherBotCommand.java` | Modify (resolve `@BotCommand.description` via `BotLabels` from ApplicationContext) |
| `src/test/java/com/kb/sessionbot/i18n/BotLabelsTest.java` | Create |
| `src/test/java/com/kb/sessionbot/i18n/ConfiguredLocaleProviderTest.java` | Create |
| `src/test/.../HelpCommand/renderer/dispatcher tests` | Modify (assert English defaults) |
