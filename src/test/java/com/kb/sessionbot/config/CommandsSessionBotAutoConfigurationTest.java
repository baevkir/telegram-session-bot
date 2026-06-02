package com.kb.sessionbot.config;

import com.kb.sessionbot.CommandsSessionBot;
import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.HelpCommand;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class CommandsSessionBotAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommandsSessionBotConfiguration.class));

    // Required properties present plus mocked client + long-polling app so the context
    // builds without real network: registerBot never runs and the startup SetMyCommands
    // call hits the mocked client.
    private final ApplicationContextRunner activeRunner = runner
            .withPropertyValues(
                    "sessionbot.telegram.token=test-token",
                    "sessionbot.telegram.bot-username=test-bot")
            .withBean(TelegramClient.class, () -> Mockito.mock(TelegramClient.class))
            .withBean(TelegramBotsLongPollingApplication.class,
                    () -> Mockito.mock(TelegramBotsLongPollingApplication.class));

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