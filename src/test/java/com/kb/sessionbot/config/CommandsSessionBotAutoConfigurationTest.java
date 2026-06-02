package com.kb.sessionbot.config;

import com.kb.sessionbot.CommandsSessionBot;
import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.HelpCommand;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class CommandsSessionBotAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommandsSessionBotConfiguration.class));

    // Required properties present plus a stub TelegramBotsApi so the real bot is never
    // registered (registerBot would start background long-polling against Telegram).
    private final ApplicationContextRunner activeRunner = runner
            .withPropertyValues(
                    "sessionbot.telegram.token=test-token",
                    "sessionbot.telegram.bot-username=test-bot")
            .withBean(TelegramBotsApi.class, () -> Mockito.mock(TelegramBotsApi.class));

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
