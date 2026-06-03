package com.kb.sessionbot.commands.dispatcher;

import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.i18n.BotLabels;
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
    private final ApplicationContext applicationContext;

    public DispatcherBotCommand(Object handler, ApplicationContext applicationContext) {
        this.commandsDispatcher = new CommandsDispatcher(handler, applicationContext);
        this.applicationContext = applicationContext;
    }

    public Publisher<? extends PartialBotApiMethod<?>> process(CommandContext commandContext) {
        Assert.isTrue(!ContextState.close.equals(commandContext.getState()), "Cannot process closed context");
        log.debug("Processing command '{}' (state={})", commandsDispatcher.getCommandId(), commandContext.getState());
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
            log.debug("Command '{}' needs more input, prompting user", commandsDispatcher.getCommandId());
            return invocationResult.getInvocationArgument();
        }
        commandContext.close();
        log.debug("Command '{}' complete, cleaning up question/answer messages", commandsDispatcher.getCommandId());
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
        return applicationContext.getBean(BotLabels.class)
            .resolve(commandsDispatcher.getCommandDescription(), (String) null);
    }

    @Override
    public String getDescription(CommandContext context) {
        return applicationContext.getBean(BotLabels.class)
            .resolve(commandsDispatcher.getCommandDescription(), context);
    }

    @Override
    public boolean hidden() {
        return commandsDispatcher.isHidden();
    }
}
