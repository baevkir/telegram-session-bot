package com.kb.sessionbot;

import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendSticker;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.Serializable;

/** Default {@link MessageExecutor} dispatching over a thread-safe {@link TelegramClient}. */
@Slf4j
public class TelegramClientMessageExecutor implements MessageExecutor {

    private final TelegramClient telegramClient;
    private final ErrorHandlerFactory errorHandler;

    public TelegramClientMessageExecutor(TelegramClient telegramClient, ErrorHandlerFactory errorHandler) {
        this.telegramClient = telegramClient;
        this.errorHandler = errorHandler;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Serializable> T execute(PartialBotApiMethod<T> message) {
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
                        .subscribe(this::execute);
                    yield null;
                }
            };
        } catch (TelegramApiException e) {
            log.error("Cannot execute message in chat (type={})", message.getClass().getSimpleName(), e);
            return null;
        }
    }
}