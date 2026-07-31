package com.kb.sessionbot;

import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.documents.DocumentHandler;
import com.kb.sessionbot.errors.exception.BotAuthException;
import com.kb.sessionbot.errors.exception.BotCommandException;
import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.ContextState;
import com.kb.sessionbot.model.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Per-chat fold/dispatch: folds a chat's {@link UpdateWrapper} stream into an evolving
 * {@link CommandContext} and dispatches the matched command, executing its emitted prompts inline
 * via {@link MessageExecutor} (so the {@code progress}-state {@code addQuestionMessage} coupling
 * sees the executed {@link Message}). Each context's results are collected, and the stream
 * completes once a command reaches {@link ContextState#close} — so the per-chat resources are
 * released promptly instead of lingering.
 */
@Slf4j
public class TelegramUpdateHandler {

    private final CommandsFactory commandsFactory;
    private final AuthInterceptor authInterceptor;
    private final MessageExecutor messageExecutor;
    private final List<DocumentHandler> documentHandlers;

    public TelegramUpdateHandler(
        CommandsFactory commandsFactory,
        AuthInterceptor authInterceptor,
        MessageExecutor messageExecutor,
        List<DocumentHandler> documentHandlers
    ) {
        this.commandsFactory = commandsFactory;
        this.authInterceptor = authInterceptor;
        this.messageExecutor = messageExecutor;
        this.documentHandlers = documentHandlers;
    }

    public Flux<PartialBotApiMethod<?>> handleUpdates(Flux<UpdateWrapper> updates) {
        Assert.notNull(updates, "Updates is null.");
        return updates
            .scanWith(CommandContext::empty, this::fold)
            .skip(1) // drop the empty seed context emitted before any update
            .concatMap(context ->
                dispatch(context)
                    .collectList()
                    .map(results -> new DispatchOutcome(context, results)))
            .takeUntil(outcome -> ContextState.close.equals(outcome.context().getState()))
            .concatMapIterable(DispatchOutcome::results);
    }

    private CommandContext fold(CommandContext context, UpdateWrapper update) {
        if (update.isCommand()) {
            return CommandContext.create(update);
        }
        if (update.getDynamicParams().needRefreshContext() && !context.isEmpty()) {
            return CommandContext.create(context.getCommandUpdate()).addUpdate(update);
        }
        return context.addUpdate(update);
    }

    private Flux<PartialBotApiMethod<?>> dispatch(CommandContext context) {
        if (context.isEmpty()) {
            return context.getCurrentUpdate()
                .flatMap(update -> update.getDocument()
                    .flatMap(document -> documentHandlers.stream()
                        .filter(handler -> handler.supports(document))
                        .findFirst()
                        .map(handler -> dispatchDocument(update, document, handler))))
                .orElseGet(() -> Flux.<PartialBotApiMethod<?>>from(commandsFactory.getHelpCommand().process(context))
                    .doOnNext(messageExecutor::execute));
        }
        log.debug("Dispatching command '{}' in chat {} (state={})", context.getCommand(), context.getChatId(), context.getState());
        return authInterceptor.intercept(context)
            .<PartialBotApiMethod<?>>flatMapMany(authorized -> {
                if (!authorized) {
                    var from = context.getCommandUpdate().getFrom();
                    var username = from != null ? from.getUserName() : "unknown";
                    log.debug("Auth rejected for command '{}' in chat {} (user={})", context.getCommand(), context.getChatId(), username);
                    return Flux.error(new BotAuthException(context, "User " + username + " is unauthorized to use bot."));
                }
                return commandsFactory.getCommand(context.getCommand()).process(context);
            })
            .doOnNext(message -> {
                var result = messageExecutor.execute(message);
                if (result instanceof Message resultMessage && ContextState.progress.equals(context.getState())) {
                    context.addQuestionMessage(resultMessage);
                }
            });
    }

    private Flux<PartialBotApiMethod<?>> dispatchDocument(UpdateWrapper update, Document document, DocumentHandler handler) {
        var documentContext = CommandContext.forUpdate(update);
        log.debug("Dispatching document '{}' in chat {}", document.getFileName(), documentContext.getChatId());
        return authInterceptor.intercept(documentContext)
            .<PartialBotApiMethod<?>>flatMapMany(authorized -> {
                if (!authorized) {
                    var from = update.getFrom();
                    var username = from != null ? from.getUserName() : "unknown";
                    return Flux.error(new BotAuthException(documentContext, "User " + username + " is unauthorized to use bot."));
                }
                return handler.handle(documentContext, document);
            })
            .onErrorMap(error -> error instanceof BotCommandException || error instanceof BotAuthException
                ? error
                : new BotCommandException(documentContext, error))
            .doOnNext(messageExecutor::execute);
    }

    /**
     * A dispatched context paired with the results it produced. Carrying the context lets the
     * pipeline complete after the outcome whose command reached {@link ContextState#close}, while
     * still emitting all of that context's results (including cleanup messages) first.
     */
    private record DispatchOutcome(CommandContext context, List<PartialBotApiMethod<?>> results) {
    }
}
