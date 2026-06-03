package com.kb.sessionbot;

import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import com.kb.sessionbot.model.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Reactive long-polling bot. Incoming updates are grouped per chat and handed to a
 * {@link TelegramUpdateHandler}; out-of-band messages from the {@link OutboundMessageBus} and
 * the startup command list are executed through a {@link MessageExecutor}.
 */
@Slf4j
public class CommandsSessionBot implements LongPollingSingleThreadUpdateConsumer {

    private final CommandsFactory commandsFactory;
    private final ErrorHandlerFactory errorHandler;
    private final MessageExecutor messageExecutor;
    private final OutboundMessageBus outboundMessageBus;
    private final TelegramUpdateHandler updateHandler;
    private final Sinks.Many<Update> updatesSink = Sinks.many().unicast().onBackpressureBuffer();
    private final Disposable.Composite subscriptions = Disposables.composite();

    public CommandsSessionBot(
        CommandsFactory commandsFactory,
        ErrorHandlerFactory errorHandler,
        MessageExecutor messageExecutor,
        OutboundMessageBus outboundMessageBus,
        TelegramUpdateHandler updateHandler
    ) {
        this.commandsFactory = commandsFactory;
        this.errorHandler = errorHandler;
        this.messageExecutor = messageExecutor;
        this.outboundMessageBus = outboundMessageBus;
        this.updateHandler = updateHandler;
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
            .map(commands -> SetMyCommands.builder().commands(commands).build())
            .subscribe(messageExecutor::execute, error -> log.error("Bot pipeline terminated unexpectedly", error));

        subscriptions.add(setMyCommands);

        subscriptions.add(
            updatesSink.asFlux()
                .map(UpdateWrapper::wrap)
                .groupBy(UpdateWrapper::getChatId)
                .flatMap(updates -> updateHandler.handleUpdates(updates.publishOn(Schedulers.boundedElastic()))
                    .onErrorResume(error -> {
                        log.warn("Handling pipeline error in a chat group", error);
                        return errorHandler.handle(error).doOnNext(messageExecutor::execute);
                    }))
                .subscribe(
                    ignored -> { },
                    error -> log.error("Bot pipeline terminated unexpectedly", error)));

        subscriptions.add(
            outboundMessageBus.messages()
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                    messageExecutor::execute,
                    error -> log.error("Bot pipeline terminated unexpectedly", error)));
    }

    @PreDestroy
    public void shutdown() {
        if (!subscriptions.isDisposed()) {
            subscriptions.dispose();
        }
    }
}