package com.kb.sessionbot.errors.handler;

import com.kb.sessionbot.errors.exception.BotAuthException;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import reactor.core.publisher.Mono;

@Slf4j
public class BotAuthErrorHandler implements ErrorHandler<BotAuthException> {

    @Override
    public Mono<? extends PartialBotApiMethod<?>> handle(BotAuthException exception) {
        log.error("Authentication failure", exception);
        return Mono.fromSupplier(() ->
                SendMessage
                        .builder()
                        .chatId(exception.getContext().getChatId())
                        .text(exception.getMessage())
                        .build()
        );
    }
}
