package com.kb.sessionbot.commands.presenter;

import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.model.CommandContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractMessagePresenterTest {

    private final AtomicInteger keyboardCalls = new AtomicInteger();

    private final AbstractMessagePresenter<String> presenter = new AbstractMessagePresenter<>() {
        @Override
        protected String buildText(String source, CommandContext context) {
            return "text:" + source;
        }

        @Override
        protected List<InlineKeyboardRow> buildKeyboard(String source, CommandContext context) {
            keyboardCalls.incrementAndGet();
            var row = new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("btn").callbackData("cb").build());
            return List.of(row);
        }
    };

    @Test
    @DisplayName("buildMessage builds the keyboard exactly once and attaches it")
    void buildMessageBuildsKeyboardOnce() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order?buy"));
        StepVerifier.create(reactor.core.publisher.Flux.from(presenter.buildMessage("hello", context)))
            .assertNext(method -> {
                assertThat(method).isInstanceOf(SendMessage.class);
                var send = (SendMessage) method;
                assertThat(send.getText()).isEqualTo("text:hello");
                assertThat(send.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
                assertThat(((InlineKeyboardMarkup) send.getReplyMarkup()).getKeyboard()).hasSize(1);
            })
            .verifyComplete();
        assertThat(keyboardCalls.get()).isEqualTo(1);
    }
}