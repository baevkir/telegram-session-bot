package com.kb.sessionbot;

import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.config.CommandsSessionBotProperties;
import com.kb.sessionbot.errors.exception.BotAuthException;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.ContextState;
import com.kb.sessionbot.model.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.methods.send.SendSticker;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.Serializable;
import java.time.Duration;

/**
 * Reactive long-polling bot. Incoming updates are grouped per chat and folded into an
 * evolving {@link CommandContext}; the matched command's results are executed against the
 * Telegram API through a {@link TelegramClient}.
 */
@Slf4j
public class CommandsSessionBot implements LongPollingSingleThreadUpdateConsumer {

    private final CommandsFactory commandsFactory;
    private final ErrorHandlerFactory errorHandler;
    private final AuthInterceptor authInterceptor;
    private final CommandsSessionBotProperties properties;
    private final TelegramClient telegramClient;
    private final Sinks.Many<Update> updatesSink = Sinks.many().unicast().onBackpressureBuffer();
    private final Sinks.Many<PartialBotApiMethod<?>> messagesSink = Sinks.many().unicast().onBackpressureBuffer();
    private Disposable subscription;

    public CommandsSessionBot(
        CommandsFactory commandsFactory,
        AuthInterceptor authInterceptor,
        ErrorHandlerFactory errorHandler,
        CommandsSessionBotProperties properties,
        TelegramClient telegramClient
    ) {
        this.commandsFactory = commandsFactory;
        this.errorHandler = errorHandler;
        this.authInterceptor = authInterceptor;
        this.properties = properties;
        this.telegramClient = telegramClient;
    }

    public void sendMessage(PartialBotApiMethod<?> message) {
        messagesSink.emitNext(message, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
    }

    @Override
    public void consume(Update update) {
        log.debug("Received update id={}", update.getUpdateId());
        updatesSink.emitNext(update, Sinks.EmitFailureHandler.FAIL_FAST);
    }

    @PostConstruct
    public void init() {
        var setMyCommands = Flux.fromIterable(commandsFactory.getCommands())
            .filter(command -> !command.hidden())
            .map(command -> BotCommand.builder().command(command.getCommandIdentifier()).description(command.getDescription()).build())
            .collectList()
            .map(commands -> SetMyCommands.builder().commands(commands).build());

        this.subscription = Flux.concat(
                setMyCommands.doOnNext(this::executeMessage),
                updatesSink.asFlux()
                    .map(UpdateWrapper::wrap)
                    .groupBy(UpdateWrapper::getChatId)
                    .flatMap(updates -> this.handleUpdates(updates.publishOn(Schedulers.boundedElastic()))
                        .onErrorResume(error -> {
                            log.warn("Handling pipeline error in a chat group", error);
                            return errorHandler.handle(error).doOnNext(this::executeMessage);
                        }))
                    .mergeWith(messagesSink.asFlux().publishOn(Schedulers.boundedElastic()).doOnNext(this::executeMessage))
            ).subscribe(
                message -> { },
                error -> log.error("Bot pipeline terminated unexpectedly", error)
            );
    }

    @PreDestroy
    public void shutdown() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    Flux<PartialBotApiMethod<?>> handleUpdates(Flux<UpdateWrapper> updates) {
        Assert.notNull(updates, "Updates is null.");
        return updates
            .scanWith(CommandContext::empty, (context, update) -> {
                if (update.isCommand()) {
                    return CommandContext.create(update);
                }
                if (update.getDynamicParams().needRefreshContext() && !context.isEmpty()) {
                    return CommandContext.create(context.getCommandUpdate()).addUpdate(update);
                }
                return context.addUpdate(update);
            })
            .skip(1)
            .concatMap(context -> {
                if (context.isEmpty()) {
                    return Flux.from(commandsFactory.getHelpCommand().process(context)).doOnNext(this::executeMessage);
                }
                log.debug("Dispatching command '{}' in chat {} (state={})", context.getCommand(), context.getChatId(), context.getState());
                return authInterceptor.intercept(context)
                    .flatMapMany(result -> {
                        if (!result) {
                            var from = context.getCommandUpdate().getFrom();
                            var username = from != null ? from.getUserName() : "unknown";
                            log.debug("Auth rejected for command '{}' in chat {} (user={})", context.getCommand(), context.getChatId(), username);
                            return Flux.error(new BotAuthException(context, "User " + username + " is unauthorized to use bot."));
                        }
                        return commandsFactory.getCommand(context.getCommand()).process(context);
                    })
                    .doOnNext(message -> {
                        var result = this.executeMessage(message);
                        if (result instanceof Message resultMessage && ContextState.progress.equals(context.getState())) {
                            context.addQuestionMessage(resultMessage);
                        }
                    });
            });
    }

    @SuppressWarnings("unchecked")
    private <T extends Serializable> T executeMessage(PartialBotApiMethod<T> message) {
        try {
            log.debug("Executing {}", message.getClass().getSimpleName());
            return switch (message) {
                case BotApiMethod<?> botApiMethod -> telegramClient.execute((BotApiMethod<T>) botApiMethod);
                case SendPhoto sendPhoto -> (T) telegramClient.execute(sendPhoto);
                case SendDocument sendDocument -> (T) telegramClient.execute(sendDocument);
                case SendVideo sendVideo -> (T) telegramClient.execute(sendVideo);
                case SendAudio sendAudio -> (T) telegramClient.execute(sendAudio);
                case SendVoice sendVoice -> (T) telegramClient.execute(sendVoice);
                case SendSticker sendSticker -> (T) telegramClient.execute(sendSticker);
                case SendAnimation sendAnimation -> (T) telegramClient.execute(sendAnimation);
                default -> {
                    log.warn("Unsupported message type {}; routing through error handler", message.getClass().getSimpleName());
                    errorHandler.handle(new UnsupportedOperationException(
                        "Message type " + message.getClass().getSimpleName() + " is not supported"))
                        .subscribe(this::executeMessage);
                    yield null;
                }
            };
        } catch (TelegramApiException e) {
            log.error("Cannot execute message in chat (type={})", message.getClass().getSimpleName(), e);
            return null;
        }
    }
}
