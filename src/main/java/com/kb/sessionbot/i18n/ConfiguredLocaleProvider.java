package com.kb.sessionbot.i18n;

import java.util.Locale;

/** Default {@link LocaleProvider}: one configured language for the whole bot, ignoring the user. */
public class ConfiguredLocaleProvider implements LocaleProvider {

    private final Locale locale;

    public ConfiguredLocaleProvider(Locale locale) {
        this.locale = locale;
    }

    @Override
    public Locale getLocale(String userName) {
        return locale;
    }
}
