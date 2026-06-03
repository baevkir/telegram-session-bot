package com.kb.sessionbot.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredLocaleProviderTest {

    @DisplayName("returns the configured locale regardless of user name (including null)")
    @Test
    void returnsConfiguredLocale() {
        var provider = new ConfiguredLocaleProvider(Locale.forLanguageTag("uk"));

        assertThat(provider.getLocale("alice")).isEqualTo(Locale.forLanguageTag("uk"));
        assertThat(provider.getLocale(null)).isEqualTo(Locale.forLanguageTag("uk"));
    }
}
