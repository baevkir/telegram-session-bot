package com.kb.sessionbot.config;

import com.kb.sessionbot.CommandsSessionBot;
import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.commands.HelpCommand;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRenderer;
import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
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
            assertThat(context).hasSingleBean(CommandsFactory.class);
            assertThat(context).hasSingleBean(AuthInterceptor.class);
            assertThat(context).hasSingleBean(ErrorHandlerFactory.class);
            assertThat(context).hasSingleBean(BotCommandErrorHandler.class);
            assertThat(context).hasSingleBean(BotAuthErrorHandler.class);
            assertThat(context).hasBean("defaultParameterRenderer");
            assertThat(context).hasBean("textParameterRenderer");
            assertThat(context).hasBean("booleanParameterRenderer");
            assertThat(context).hasBean("dateParameterRenderer");
            assertThat(context.getBeansOfType(ParameterRenderer.class)).hasSize(4);
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

    @Test
    void allowsDownstreamToOverrideDefaultParameterRenderer() {
        ParameterRenderer custom = request -> Mono.empty();
        activeRunner.withBean("defaultParameterRenderer", ParameterRenderer.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("defaultParameterRenderer", ParameterRenderer.class)).isSameAs(custom);
        });
    }
}