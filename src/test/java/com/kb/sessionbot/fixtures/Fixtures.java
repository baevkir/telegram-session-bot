package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.UpdateWrapper;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/** Builds Telegram DTOs and session objects from wire strings for tests. */
public final class Fixtures {

    public static final long CHAT_ID = 4242L;

    private Fixtures() {
    }

    public static User user(String username) {
        return User.builder()
            .id(7L)
            .userName(username)
            .firstName("Test")
            .isBot(false)
            .build();
    }

    public static Message message(long chatId, int messageId, String text) {
        return Message.builder()
            .messageId(messageId)
            .chat(Chat.builder().id(chatId).type("private").build())
            .from(user("tester"))
            .text(text)
            .build();
    }

    /** A message-based update; command-ness is driven by the leading '/' in text. */
    public static Update messageUpdate(int updateId, long chatId, int messageId, String text) {
        var update = new Update();
        update.setUpdateId(updateId);
        update.setMessage(message(chatId, messageId, text));
        return update;
    }

    /** A message-based update carrying a document and no text. */
    public static Update documentUpdate(int updateId, long chatId, int messageId, String fileName) {
        var document = new Document();
        document.setFileId("file-" + updateId);
        document.setFileUniqueId("ufile-" + updateId);
        document.setFileName(fileName);
        var message = Message.builder()
            .messageId(messageId)
            .chat(Chat.builder().id(chatId).type("private").build())
            .from(user("tester"))
            .document(document)
            .build();
        var update = new Update();
        update.setUpdateId(updateId);
        update.setMessage(message);
        return update;
    }

    /** A callback-query update carrying wire data; its message is the question message. */
    public static Update callbackUpdate(int updateId, long chatId, int questionMessageId, String data) {
        var callback = new CallbackQuery();
        callback.setId("cb-" + updateId);
        callback.setData(data);
        callback.setFrom(user("tester"));
        callback.setMessage(message(chatId, questionMessageId, "question"));
        var update = new Update();
        update.setUpdateId(updateId);
        update.setCallbackQuery(callback);
        return update;
    }

    public static UpdateWrapper wrap(Update update) {
        return UpdateWrapper.wrap(update);
    }

    public static UpdateWrapper commandWrapper(String wire) {
        return wrap(messageUpdate(1, CHAT_ID, 100, wire));
    }

    public static UpdateWrapper answerWrapper(int updateId, int questionMessageId, String wire) {
        return wrap(callbackUpdate(updateId, CHAT_ID, questionMessageId, wire));
    }

    public static CommandContext contextFor(String commandWire) {
        return CommandContext.create(commandWrapper(commandWire));
    }
}