package com.kb.sessionbot.model;

import com.kb.sessionbot.fixtures.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateWrapperTest {

    @Test
    @DisplayName("wrap rejects null update")
    void wrapNull() {
        assertThatThrownBy(() -> UpdateWrapper.wrap(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("Update is null.");
    }

    @Test
    @DisplayName("wrap of update with neither message nor callback throws on empty text parse")
    void wrapEmptyTextThrows() {
        var update = new Update();
        update.setUpdateId(1);
        assertThatThrownBy(() -> UpdateWrapper.wrap(update))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("text is empty");
    }

    @Nested
    @DisplayName("getChatId")
    class ChatId {

        @Test
        void fromMessage() {
            var wrapper = UpdateWrapper.wrap(Fixtures.messageUpdate(1, 99L, 10, "/order"));
            assertThat(wrapper.getChatId()).isEqualTo("99");
        }

        @Test
        void fromCallbackQuery() {
            var wrapper = UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 77L, 10, "book"));
            assertThat(wrapper.getChatId()).isEqualTo("77");
        }
    }

    @Nested
    @DisplayName("isCommand OR-logic")
    class IsCommand {

        @Test
        void trueWhenDescriptorIsCommand() {
            assertThat(UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "/order")).isCommand()).isTrue();
        }

        @Test
        void falseForPlainText() {
            assertThat(UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "book")).isCommand()).isFalse();
        }

        @Test
        void trueWhenCallbackDataIsCommand() {
            assertThat(UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 1, "/order")).isCommand()).isTrue();
        }

        @Test
        void falseForCallbackAnswerData() {
            assertThat(UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 1, "book")).isCommand()).isFalse();
        }
    }

    @Nested
    @DisplayName("getFrom")
    class From {

        @Test
        void fromMessage() {
            var wrapper = UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "/order"));
            assertThat(wrapper.getFrom().getUserName()).isEqualTo("tester");
        }

        @Test
        void fromCallback() {
            var wrapper = UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 1, "book"));
            assertThat(wrapper.getFrom().getUserName()).isEqualTo("tester");
        }
    }

    @Test
    @DisplayName("getCallbackMessage present for callback, empty for message")
    void callbackMessage() {
        var callbackWrapper = UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 33, "book"));
        assertThat(callbackWrapper.getCallbackMessage()).isPresent();
        assertThat(callbackWrapper.getCallbackMessage().get().getMessageId()).isEqualTo(33);

        var messageWrapper = UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "/order"));
        assertThat(messageWrapper.getCallbackMessage()).isEmpty();
    }

    @Test
    @DisplayName("getMessageId present for message, empty for callback")
    void messageId() {
        assertThat(UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 12, "/order")).getMessageId()).contains(12);
        assertThat(UpdateWrapper.wrap(Fixtures.callbackUpdate(2, 1L, 1, "book")).getMessageId()).isEmpty();
    }

    @Test
    @DisplayName("getAnswers and getCommand delegate to descriptor")
    void delegation() {
        var wrapper = UpdateWrapper.wrap(Fixtures.messageUpdate(1, 1L, 1, "/order?buy&book"));
        assertThat(wrapper.getCommand()).isEqualTo("order");
        assertThat(wrapper.getAnswers()).containsExactly("buy", "book");
    }
}