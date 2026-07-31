package com.kb.sessionbot.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.*;

@Slf4j
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UpdateWrapper {
    private Update update;
    private MessageDescriptor messageDescriptor;

    public static UpdateWrapper wrap(Update update) {
        return new UpdateWrapper(
            Objects.requireNonNull(update, "Update is null."),
            getText(update).filter(StringUtils::hasText).map(MessageDescriptor::parse).orElseGet(MessageDescriptor::empty)
        );
    }

    public String getChatId() {
        if (update.hasMessage()) {
            return String.valueOf(update.getMessage().getChatId());
        }
        if (update.hasCallbackQuery()) {
            CallbackQuery callbackQuery = update.getCallbackQuery();
            return String.valueOf(callbackQuery.getMessage().getChatId());
        }
        log.error("Cannot get chat id from update id={} (type={})", update.getUpdateId(), describeType(update));
        throw new RuntimeException("Cannot get chat id from update");
    }

    public Optional<Integer> getMessageId() {
        if (!update.hasMessage()) {
            return Optional.empty();
        }
        return Optional.ofNullable(update.getMessage()).map(Message::getMessageId);
    }

    public boolean isCommand() {
        return (update.hasMessage() && update.getMessage().isCommand()) || messageDescriptor.isCommand();
    }

    public String getCommand() {
        return messageDescriptor.getCommand();
    }

    public User getFrom() {
        return Optional.ofNullable(update.getMessage())
            .map(Message::getFrom)
            .or(() -> Optional.ofNullable(update.getCallbackQuery()).map(CallbackQuery::getFrom))
            .orElse(null);
    }

    public List<String> getAnswers() {
        return messageDescriptor.getAnswers();
    }

    public Optional<MaybeInaccessibleMessage> getCallbackMessage() {
        return Optional.of(update)
            .filter(Update::hasCallbackQuery)
            .map(Update::getCallbackQuery)
            .map(CallbackQuery::getMessage);
    }

    public Optional<Document> getDocument() {
        return Optional.ofNullable(update.getMessage()).map(Message::getDocument);
    }

    public DynamicParameters getDynamicParams() {
        return messageDescriptor.getDynamicParams();
    }

    private static Optional<String> getText(Update update) {
        if (update.hasMessage()) {
            return Optional.ofNullable(update.getMessage().getText());
        }
        if (update.hasCallbackQuery()) {
            return Optional.of(update.getCallbackQuery().getData());
        }
        return Optional.empty();
    }

    private static String describeType(Update update) {
        if (update.hasMessage()) return "message";
        if (update.hasCallbackQuery()) return "callbackQuery";
        if (update.hasEditedMessage()) return "editedMessage";
        if (update.hasInlineQuery()) return "inlineQuery";
        return "other";
    }
}
