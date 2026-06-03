package com.kb.sessionbot.config;

import com.kb.sessionbot.CommandsSessionBot;
import com.kb.sessionbot.MessageExecutor;
import com.kb.sessionbot.OutboundMessages;
import com.kb.sessionbot.TelegramClientMessageExecutor;
import com.kb.sessionbot.TelegramUpdateHandler;
import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.commands.HelpCommand;
import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.commands.dispatcher.DispatcherBotCommand;
import com.kb.sessionbot.commands.dispatcher.annotations.BotCommand;
import com.kb.sessionbot.commands.dispatcher.parameters.*;
import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Auto-configuration for the session bot. Activates when {@code sessionbot.telegram.token}
 * and {@code bot-username} are set, wiring the bot, its {@link TelegramClient} and
 * long-polling registration, command dispatch, parameter renderers, auth and error
 * handling. Most beans are {@code @ConditionalOnMissingBean} so a consuming app can override
 * any of them by declaring its own.
 */
@AutoConfiguration
@ConditionalOnProperty(value = {"token", "bot-username"}, prefix = "sessionbot.telegram")
@EnableConfigurationProperties(CommandsSessionBotProperties.class)
public class CommandsSessionBotConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TelegramClient telegramClient(CommandsSessionBotProperties properties) {
        return new OkHttpTelegramClient(properties.getToken());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public TelegramBotsLongPollingApplication telegramBotsApplication(
            CommandsSessionBot bot, CommandsSessionBotProperties properties) throws TelegramApiException {
        var application = new TelegramBotsLongPollingApplication();
        application.registerBot(properties.getToken(), bot);
        return application;
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageExecutor messageExecutor(TelegramClient telegramClient, ErrorHandlerFactory errorHandler) {
        return new TelegramClientMessageExecutor(telegramClient, errorHandler);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundMessages outboundMessages() {
        return new OutboundMessages();
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramUpdateHandler telegramUpdateHandler(
            CommandsFactory commandsFactory,
            AuthInterceptor authInterceptor,
            MessageExecutor messageExecutor) {
        return new TelegramUpdateHandler(commandsFactory, authInterceptor, messageExecutor);
    }

    @Bean
    public CommandsSessionBot bot(
            CommandsFactory commandsFactory,
            ErrorHandlerFactory errorHandler,
            MessageExecutor messageExecutor,
            OutboundMessages outboundMessages,
            TelegramUpdateHandler telegramUpdateHandler) {
        return new CommandsSessionBot(commandsFactory, errorHandler, messageExecutor, outboundMessages, telegramUpdateHandler);
    }


    @Bean
    public List<IBotCommand> reactiveBotCommand(ApplicationContext applicationContext) {
        return applicationContext.getBeansWithAnnotation(BotCommand.class)
                .values()
                .stream()
                .map(handler -> new DispatcherBotCommand(handler, applicationContext))
                .collect(Collectors.toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public HelpCommand helpCommand(List<IBotCommand> botCommands) {
        return new HelpCommand(botCommands);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandsFactory commandsFactory(HelpCommand helpCommand) {
        return new CommandsFactory(helpCommand, helpCommand.getBotCommands());
    }

    @Bean
    @ConditionalOnMissingBean(name = "defaultParameterRenderer")
    public ParameterRenderer defaultParameterRenderer(ParameterRenderer textParameterRenderer, ParameterRenderer dateParameterRenderer, ParameterRenderer booleanParameterRenderer) {
        return new ParameterRendererFactory(textParameterRenderer, dateParameterRenderer, booleanParameterRenderer);
    }

    @Bean
    @ConditionalOnMissingBean(name = "textParameterRenderer")
    public ParameterRenderer textParameterRenderer() {
        return new TextParameterRenderer();
    }

    @Bean
    @ConditionalOnMissingBean(name = "booleanParameterRenderer")
    public ParameterRenderer booleanParameterRenderer() {
        return new BooleanParameterRenderer();
    }

    @Bean
    @ConditionalOnMissingBean(name = "dateParameterRenderer")
    public ParameterRenderer dateParameterRenderer() {
        return new DateParameterRenderer();
    }

    @Bean
    public ErrorHandlerFactory errorHandlerFactory(List<ErrorHandler<?>> errorHandlers) {
        return new ErrorHandlerFactory(errorHandlers);
    }

    @Bean
    @ConditionalOnMissingBean(name = "botCommandErrorHandler")
    public BotCommandErrorHandler botCommandErrorHandler() {
        return new BotCommandErrorHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "botAuthErrorHandler")
    public BotAuthErrorHandler botAuthErrorHandler() {
        return new BotAuthErrorHandler();
    }


    @Bean
    @ConditionalOnMissingBean
    public AuthInterceptor authInterceptor() {
        return request -> Mono.just(true);
    }
}
