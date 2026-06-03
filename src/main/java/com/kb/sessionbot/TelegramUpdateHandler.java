package com.kb.sessionbot;

import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.errors.exception.BotAuthException;
import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.ContextState;
import com.kb.sessionbot.model.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
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

    public TelegramUpdateHandler(
        CommandsFactory commandsFactory,
        AuthInterceptor authInterceptor,
        MessageExecutor messageExecutor
    ) {
        this.commandsFactory = commandsFactory;
        this.authInterceptor = authInterceptor;
        this.messageExecutor = messageExecutor;
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
            return Flux.<PartialBotApiMethod<?>>from(commandsFactory.getHelpCommand().process(context)).doOnNext(messageExecutor::execute);
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

    /**
     * A dispatched context paired with the results it produced. Carrying the context lets the
     * pipeline complete after the outcome whose command reached {@link ContextState#close}, while
     * still emitting all of that context's results (including cleanup messages) first.
     */
    private record DispatchOutcome(CommandContext context, List<PartialBotApiMethod<?>> results) {
    }
}
