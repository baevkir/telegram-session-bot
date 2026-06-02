package com.kb.sessionbot.errors.handler;

import com.kb.sessionbot.errors.exception.BotCommandException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Slf4j
public class BotCommandErrorHandler implements ErrorHandler<BotCommandException> {

    @Override
    public Mono<? extends PartialBotApiMethod<?>> handle(BotCommandException exception) {
        String botMessage = Optional.ofNullable(ExceptionUtils.getRootCause(exception).getMessage())
                .orElse("Error during chat bot command. Please try again letter.");
        log.error("Command failed: {}", botMessage, exception);
        return Mono.fromSupplier(() ->
                SendMessage
                        .builder()
                        .chatId(exception.getContext().getChatId())
                        .text(botMessage)
                        .build()
        );
    }
}
