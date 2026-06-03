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
