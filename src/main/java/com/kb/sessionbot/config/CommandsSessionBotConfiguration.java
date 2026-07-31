package com.kb.sessionbot.config;

import com.kb.sessionbot.CommandsSessionBot;
import com.kb.sessionbot.InboundUpdateBus;
import com.kb.sessionbot.MessageExecutor;
import com.kb.sessionbot.OutboundMessageBus;
import com.kb.sessionbot.SinkInboundUpdateBus;
import com.kb.sessionbot.SinkOutboundMessageBus;
import com.kb.sessionbot.TelegramClientMessageExecutor;
import com.kb.sessionbot.TelegramUpdateHandler;
import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.commands.HelpCommand;
import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.commands.dispatcher.DispatcherBotCommand;
import com.kb.sessionbot.commands.dispatcher.annotations.BotCommand;
import com.kb.sessionbot.commands.dispatcher.parameters.*;
import com.kb.sessionbot.documents.DocumentHandler;
import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import com.kb.sessionbot.i18n.BotLabels;
import com.kb.sessionbot.i18n.ConfiguredLocaleProvider;
import com.kb.sessionbot.i18n.LocaleProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
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
    public OutboundMessageBus outboundMessageBus() {
        return new SinkOutboundMessageBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramUpdateHandler telegramUpdateHandler(
            CommandsFactory commandsFactory,
            AuthInterceptor authInterceptor,
            MessageExecutor messageExecutor,
            ObjectProvider<DocumentHandler> documentHandlers) {
        return new TelegramUpdateHandler(commandsFactory, authInterceptor, messageExecutor,
            documentHandlers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public InboundUpdateBus inboundUpdateBus(CommandsSessionBotProperties properties) {
        return new SinkInboundUpdateBus(properties.getChatIdleTtl());
    }

    @Bean
    public CommandsSessionBot bot(
            CommandsFactory commandsFactory,
            ErrorHandlerFactory errorHandler,
            MessageExecutor messageExecutor,
            OutboundMessageBus outboundMessageBus,
            TelegramUpdateHandler telegramUpdateHandler,
            InboundUpdateBus inboundUpdateBus,
            CommandsSessionBotProperties properties) {
        return new CommandsSessionBot(commandsFactory, errorHandler, messageExecutor,
            outboundMessageBus, telegramUpdateHandler, inboundUpdateBus, properties.getMaxConcurrentChats());
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
    public HelpCommand helpCommand(List<IBotCommand> botCommands, BotLabels botLabels) {
        return new HelpCommand(botCommands, botLabels);
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
    public ParameterRenderer textParameterRenderer(BotLabels botLabels) {
        return new TextParameterRenderer(botLabels);
    }

    @Bean
    @ConditionalOnMissingBean(name = "booleanParameterRenderer")
    public ParameterRenderer booleanParameterRenderer(BotLabels botLabels) {
        return new BooleanParameterRenderer(botLabels);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dateParameterRenderer")
    public ParameterRenderer dateParameterRenderer(BotLabels botLabels) {
        return new DateParameterRenderer(botLabels);
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

    @Bean
    @ConditionalOnMissingBean(name = "sessionbotLabelsMessageSource")
    public MessageSource sessionbotLabelsMessageSource(
            @Qualifier("messageSource") ObjectProvider<MessageSource> appMessageSource) {
        var ms = new ResourceBundleMessageSource();
        ms.setBasenames("sessionbot-labels-override", "sessionbot-labels");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        ms.setUseCodeAsDefaultMessage(false);
        appMessageSource.ifAvailable(ms::setParentMessageSource);
        return ms;
    }

    @Bean
    @ConditionalOnMissingBean
    public LocaleProvider localeProvider(CommandsSessionBotProperties properties) {
        return new ConfiguredLocaleProvider(Locale.forLanguageTag(properties.getLanguage()));
    }

    @Bean
    @ConditionalOnMissingBean
    public BotLabels botLabels(
            @Qualifier("sessionbotLabelsMessageSource") MessageSource sessionbotLabelsMessageSource,
            LocaleProvider localeProvider) {
        return new BotLabels(sessionbotLabelsMessageSource, localeProvider);
    }
}
