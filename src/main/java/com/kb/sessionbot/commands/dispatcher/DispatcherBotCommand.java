package com.kb.sessionbot.commands.dispatcher;

import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.context.ApplicationContext;
import org.telegram.telegrambots.meta.api.methods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
public class DispatcherBotCommand implements IBotCommand {

    private final CommandsDispatcher commandsDispatcher;

    public DispatcherBotCommand(Object handler, ApplicationContext applicationContext) {
        this.commandsDispatcher = new CommandsDispatcher(handler, applicationContext);
    }

    public Publisher<? extends PartialBotApiMethod<?>> process(CommandContext commandContext) {
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
            return invocationResult.getInvocationArgument();
        }
        var removeOldMessages = Flux.<Integer>create(sink -> {
//                var commandUpdate = commandContext.getCommandUpdate();
//                var commandMessageId = commandUpdate.getMessageId()
//                    .or(() -> commandUpdate.getCallbackMessage().map(Message::getMessageId))
//                    .orElse(null);
//                commandContext.getMessages().stream()
//                    .map(Message::getMessageId)
//                    .filter(messageId -> !Objects.equals(commandMessageId, messageId))
//                    .forEach(sink::next);

                commandContext.getUpdates().forEach(update -> {
                    update.getMessageId().ifPresent(sink::next);
                    update.getCallbackMessage().map(Message::getMessageId).ifPresent(sink::next);
                });
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
