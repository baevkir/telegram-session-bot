package com.kb.sessionbot.i18n;

import com.kb.sessionbot.config.CommandsSessionBotConfiguration;
import com.kb.sessionbot.config.CommandsSessionBotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@link CommandsSessionBotConfiguration} i18n bean methods (locale from the
 * configured language, the label {@code MessageSource} with its override + parent chain, and the
 * {@code BotLabels} facade) — the wiring that unit tests on the classes alone do not cover.
 */
class I18nAutoConfigurationTest {

    private final CommandsSessionBotConfiguration config = new CommandsSessionBotConfiguration();

    @DisplayName("configured language=uk resolves built-in labels to Ukrainian through the wired beans")
    @Test
    void configuredLanguageResolvesToUkrainian() {
        var properties = new CommandsSessionBotProperties();
        properties.setLanguage("uk");

        var labels = config.botLabels(
            config.sessionbotLabelsMessageSource(noParent()),
            config.localeProvider(properties));

        assertThat(labels.helpTitle(null)).isEqualTo("Довідка");
        assertThat(labels.skip(null)).isEqualTo("Пропустити");
        assertThat(labels.missingParameter(null, "product")).isEqualTo("Будь ласка, вкажіть поле 'product'.");
    }

    @DisplayName("default language is English")
    @Test
    void defaultLanguageIsEnglish() {
        var labels = config.botLabels(
            config.sessionbotLabelsMessageSource(noParent()),
            config.localeProvider(new CommandsSessionBotProperties()));

        assertThat(labels.helpTitle(null)).isEqualTo("Help");
    }

    @DisplayName("consumer {key} annotation text resolves through the parent MessageSource chain")
    @Test
    void consumerKeyResolvesViaParentChain() {
        var consumer = new ResourceBundleMessageSource();
        consumer.setBasenames("test-consumer-labels");
        consumer.setDefaultEncoding("UTF-8");
        consumer.setFallbackToSystemLocale(false);

        var labels = config.botLabels(
            config.sessionbotLabelsMessageSource(withParent(consumer)),
            config.localeProvider(new CommandsSessionBotProperties()));

        assertThat(labels.resolve("{consumer.greeting}", (String) null)).isEqualTo("Hello");
        assertThat(labels.resolve("{missing.key:fallback}", (String) null)).isEqualTo("fallback");
    }

    private static ObjectProvider<MessageSource> noParent() {
        return provider(null);
    }

    private static ObjectProvider<MessageSource> withParent(MessageSource parent) {
        return provider(parent);
    }

    private static ObjectProvider<MessageSource> provider(MessageSource value) {
        return new ObjectProvider<>() {
            @Override public MessageSource getObject() { return value; }
            @Override public MessageSource getObject(Object... args) { return value; }
            @Override public MessageSource getIfAvailable() { return value; }
            @Override public MessageSource getIfUnique() { return value; }
        };
    }
}
