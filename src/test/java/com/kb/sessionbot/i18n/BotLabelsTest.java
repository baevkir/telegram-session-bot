package com.kb.sessionbot.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.DayOfWeek;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class BotLabelsTest {

    private BotLabels labels(Locale locale) {
        var ms = new ResourceBundleMessageSource();
        ms.setBasenames("sessionbot-labels");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        var parent = new ResourceBundleMessageSource();
        parent.setBasenames("test-consumer-labels");
        parent.setDefaultEncoding("UTF-8");
        parent.setFallbackToSystemLocale(false);
        ms.setParentMessageSource(parent);
        return new BotLabels(ms, userName -> locale);
    }

    @DisplayName("typed accessors resolve from the configured locale bundle")
    @Test
    void typedAccessorsResolve() {
        assertThat(labels(Locale.ENGLISH).helpTitle(null)).isEqualTo("Help");
        assertThat(labels(Locale.forLanguageTag("uk")).helpTitle(null)).isEqualTo("Довідка");
        assertThat(labels(Locale.ENGLISH).weekday(null, DayOfWeek.SUNDAY)).isEqualTo("Sun");
        assertThat(labels(Locale.forLanguageTag("uk")).weekday(null, DayOfWeek.SUNDAY)).isEqualTo("Нд");
    }

    @DisplayName("missingParameter formats the field argument")
    @Test
    void missingParameterFormatsArg() {
        assertThat(labels(Locale.ENGLISH).missingParameter(null, "product"))
            .isEqualTo("Please provide the field 'product'.");
    }

    @DisplayName("resolve: {key} resolves from the parent message source")
    @Test
    void resolveKnownKey() {
        assertThat(labels(Locale.ENGLISH).resolve("{consumer.greeting}", (String) null)).isEqualTo("Hello");
    }

    @DisplayName("resolve: unknown {key} falls back to the literal; {key:default} uses the default")
    @Test
    void resolveFallbacks() {
        assertThat(labels(Locale.ENGLISH).resolve("{missing.key}", (String) null)).isEqualTo("{missing.key}");
        assertThat(labels(Locale.ENGLISH).resolve("{missing.key:Fallback}", (String) null)).isEqualTo("Fallback");
    }

    @DisplayName("resolve: literal and partial-brace text pass through unchanged")
    @Test
    void resolveLiteral() {
        assertThat(labels(Locale.ENGLISH).resolve("Health check", (String) null)).isEqualTo("Health check");
        assertThat(labels(Locale.ENGLISH).resolve("Order {0} items", (String) null)).isEqualTo("Order {0} items");
    }

    @DisplayName("resolve(text, userName) uses the per-user locale (for outbound messages)")
    @Test
    void resolveByUserName() {
        var ms = new ResourceBundleMessageSource();
        ms.setBasenames("sessionbot-labels");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        var labels = new BotLabels(ms, userName ->
            "bob".equals(userName) ? Locale.forLanguageTag("uk") : Locale.ENGLISH);

        assertThat(labels.resolve("{help.title}", "bob")).isEqualTo("Довідка");
        assertThat(labels.resolve("{help.title}", "alice")).isEqualTo("Help");
        assertThat(labels.resolve("{help.title}", (String) null)).isEqualTo("Help");
    }
}
