package com.kb.sessionbot.commands.dispatcher;

import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.ContextState;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.context.ApplicationContext;
import org.springframework.util.Assert;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/**
 * Adapts a {@code @BotCommand} bean to {@link IBotCommand}. Runs one dispatch step,
 * suspending the context in {@code progress} when more input is needed, and on completion
 * deletes the chat's question and answer messages to keep the conversation clean.
 */
@Slf4j
public class DispatcherBotCommand implements IBotCommand {

    private final CommandsDispatcher commandsDispatcher;

    public DispatcherBotCommand(Object handler, ApplicationContext applicationContext) {
        this.commandsDispatcher = new CommandsDispatcher(handler, applicationContext);
    }

    public Publisher<? extends PartialBotApiMethod<?>> process(CommandContext commandContext) {
        Assert.isTrue(!ContextState.close.equals(commandContext.getState()), "Cannot process closed context");
        var invocationResult = commandsDispatcher.invoke(commandContext);
        if (invocationResult.hasErrors()) {
            return Mono.error(invocationResult.getInvocationError());
        }
        var pendingArguments = commandContext.getPendingArguments();
        if (pendingArguments.isEmpty() && commandContext.getDynamicParams().canScipAnswer(0)) {
            commandContext.addAnswer("");
        } else {
            pendingArguments.forEach(commandContext::addAnswer);
        }
        if (invocationResult.getInvocationArgument() != null) {
            commandContext.startProgress();
            return invocationResult.getInvocationArgument();
        }
        commandContext.close();
        var removeOldMessages = Flux.<Integer>create(sink -> {
                commandContext.getQuestionMessages().stream()
                    .map(Message::getMessageId)
                    .forEach(sink::next);

                commandContext.getUpdates().forEach(update -> {
                    update.getMessageId().ifPresent(sink::next);
                    update.getCallbackMessage().map(MaybeInaccessibleMessage::getMessageId).ifPresent(sink::next);
                });
                sink.complete();
            })
            .distinct()
            .map(messageId ->
                DeleteMessage.builder()
                    .chatId(commandContext.getChatId())
                    .messageId(messageId)
                    .build()
            );
        return Flux.concat(
            invocationResult.getInvocation(),
            removeOldMessages
        );
    }

    @Override
    public String getCommandIdentifier() {
        return commandsDispatcher.getCommandId();
    }

    @Override
    public String getDescription() {
        return commandsDispatcher.getCommandDescription();
    }

    @Override
    public boolean hidden() {
        return commandsDispatcher.isHidden();
    }
}
