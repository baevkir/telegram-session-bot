package com.kb.sessionbot.i18n;

import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.UpdateWrapper;
import org.springframework.context.MessageSource;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.DayOfWeek;
import java.util.Optional;

/**
 * Facade for built-in label resolution. Typed accessors resolve library label keys from the
 * configured-locale bundle; {@link #resolve} handles consumer-authored {@code {key}} /
 * {@code {key:default}} annotation text. Locale comes from {@link LocaleProvider} keyed on the
 * sender's user name (taken from the {@link CommandContext}; may be null).
 */
public class BotLabels {

    private final MessageSource messages;
    private final LocaleProvider localeProvider;

    public BotLabels(MessageSource messages, LocaleProvider localeProvider) {
        this.messages = messages;
        this.localeProvider = localeProvider;
    }

    public String helpTitle(CommandContext ctx)        { return get("help.title", ctx); }
    public String helpIntro(CommandContext ctx)        { return get("help.intro", ctx); }
    public String helpDescription()                    { return get("help.description", null); }
    public String skip(CommandContext ctx)             { return get("button.skip", ctx); }
    public String yes(CommandContext ctx)              { return get("button.yes", ctx); }
    public String no(CommandContext ctx)               { return get("button.no", ctx); }

    public String missingParameter(CommandContext ctx, String field) {
        return get("param.missing", ctx, field);
    }

    public String unsupportedOptions(CommandContext ctx, Object options, Object command) {
        return get("command.unsupportedOptions", ctx, options, command);
    }

    public String dateFormatHint(CommandContext ctx, String label, String format) {
        return get("date.formatHint", ctx, label, format);
    }

    public String weekday(CommandContext ctx, DayOfWeek day) {
        return get("weekday." + day.name().toLowerCase().substring(0, 3), ctx);
    }

    public String resolve(String text, CommandContext ctx) {
        return resolve(text, userName(ctx));
    }

    /**
     * Resolve by user name directly — for out-of-band (outbound) messages that have no incoming
     * {@link CommandContext}. Pass {@code null} for the configured/bot-wide locale.
     */
    public String resolve(String text, String userName) {
        if (text == null) {
            return null;
        }
        var trimmed = text.trim();
        if (trimmed.length() > 2 && trimmed.startsWith("{") && trimmed.endsWith("}")
                && trimmed.indexOf('}') == trimmed.length() - 1) {
            var inner = trimmed.substring(1, trimmed.length() - 1);
            int sep = inner.indexOf(':');
            var code = (sep >= 0 ? inner.substring(0, sep) : inner).trim();
            var fallback = sep >= 0 ? inner.substring(sep + 1) : text;
            return messages.getMessage(code, null, fallback, localeProvider.getLocale(userName));
        }
        return text;
    }

    private String get(String key, CommandContext ctx, Object... args) {
        return messages.getMessage(key, args, localeProvider.getLocale(userName(ctx)));
    }

    private String userName(CommandContext ctx) {
        return ctx == null ? null
            : Optional.ofNullable(ctx.getCommandUpdate())
                      .map(UpdateWrapper::getFrom)
                      .map(User::getUserName)
                      .orElse(null);
    }
}
