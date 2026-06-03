package com.kb.sessionbot;

import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import com.kb.sessionbot.fixtures.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

class MessageExecutorTest {

    private TelegramClient telegramClient;
    private ErrorHandlerFactory errorHandlerFactory;
    private MessageExecutor executor;

    @BeforeEach
    void setUp() {
        telegramClient = Mockito.mock(TelegramClient.class);
        errorHandlerFactory = new ErrorHandlerFactory(
            List.<ErrorHandler<?>>of(new BotCommandErrorHandler(), new BotAuthErrorHandler()));
        errorHandlerFactory.init();
        executor = new TelegramClientMessageExecutor(telegramClient, errorHandlerFactory);
    }

    @DisplayName("a BotApiMethod dispatches to the generic TelegramClient.execute overload and returns its result")
    @Test
    void botApiMethodDispatchesToGenericOverload() throws Exception {
        var sent = Fixtures.message(Fixtures.CHAT_ID, 999, "sent");
        Mockito.when(telegramClient.execute(any(BotApiMethod.class))).thenReturn(sent);

        Message result = executor.execute(SendMessage.builder()
            .chatId(String.valueOf(Fixtures.CHAT_ID)).text("hi").build());

        assertThat(result).isSameAs(sent);
        verify(telegramClient).execute(any(BotApiMethod.class));
    }

    @DisplayName("SendPhoto dispatches to the typed TelegramClient.execute(SendPhoto) overload")
    @Test
    void sendPhotoDispatchesToTypedOverload() throws Exception {
        Mockito.when(telegramClient.execute(any(SendPhoto.class)))
            .thenReturn(Fixtures.message(Fixtures.CHAT_ID, 1, "photo"));

        executor.execute(SendPhoto.builder()
            .chatId(String.valueOf(Fixtures.CHAT_ID))
            .photo(new InputFile("file_id_photo"))
            .build());

        verify(telegramClient).execute(any(SendPhoto.class));
    }

    @DisplayName("SendDocument dispatches to the typed TelegramClient.execute(SendDocument) overload")
    @Test
    void sendDocumentDispatchesToTypedOverload() throws Exception {
        Mockito.when(telegramClient.execute(any(SendDocument.class)))
            .thenReturn(Fixtures.message(Fixtures.CHAT_ID, 2, "doc"));

        executor.execute(SendDocument.builder()
            .chatId(String.valueOf(Fixtures.CHAT_ID))
            .document(new InputFile("file_id_doc"))
            .build());

        verify(telegramClient).execute(any(SendDocument.class));
    }
}