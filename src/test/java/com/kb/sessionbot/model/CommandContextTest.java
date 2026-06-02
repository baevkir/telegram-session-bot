package com.kb.sessionbot.model;

import com.kb.sessionbot.fixtures.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandContextTest {

    @Nested
    @DisplayName("creation guards")
    class Guards {

        @Test
        void createRejectsNonCommandUpdate() {
            var answer = Fixtures.commandWrapper("buy&book"); // no leading '/', not a command
            assertThatThrownBy(() -> CommandContext.create(answer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Context should be created only for command.");
        }

        @Test
        void addUpdateRejectsCommandUpdate() {
            var context = CommandContext.create(Fixtures.commandWrapper("/order"));
            var command = Fixtures.commandWrapper("/order");
            assertThatThrownBy(() -> context.addUpdate(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Command should create new context");
        }
    }

    @Test
    @DisplayName("empty() is empty and create() is open")
    void emptyAndCreateState() {
        assertThat(CommandContext.empty().isEmpty()).isTrue();
        var context = CommandContext.create(Fixtures.commandWrapper("/order"));
        assertThat(context.isEmpty()).isFalse();
        assertThat(context.getState()).isEqualTo(ContextState.open);
        assertThat(context.getCommand()).isEqualTo("order");
    }

    @Test
    @DisplayName("getAnswers merges command answers and pending arguments")
    void answersMerge() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order?buy"));
        assertThat(context.getAnswers()).containsExactly("buy");

        context.addUpdate(Fixtures.answerWrapper(2, 100, "book"));
        assertThat(context.getAnswers()).containsExactly("buy", "book");
        assertThat(context.getPendingArguments()).containsExactly("book");
    }

    @Test
    @DisplayName("lifecycle open -> progress -> close")
    void lifecycle() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order"));
        assertThat(context.getState()).isEqualTo(ContextState.open);
        context.startProgress();
        assertThat(context.getState()).isEqualTo(ContextState.progress);
        context.close();
        assertThat(context.getState()).isEqualTo(ContextState.close);
    }

    @Test
    @DisplayName("getChatId falls back from command update to current update, null when empty")
    void chatId() {
        assertThat(CommandContext.empty().getChatId()).isNull();
        var context = CommandContext.create(Fixtures.commandWrapper("/order"));
        assertThat(context.getChatId()).isEqualTo(String.valueOf(Fixtures.CHAT_ID));
    }

    @Test
    @DisplayName("refreshContext rebuild keeps command answers and re-applies the latest update")
    void refreshRebuildSemantics() {
        // Mirrors CommandsSessionBot.handleUpdates: rebuild from the original command update,
        // then re-add the current (refresh) update.
        var command = Fixtures.commandWrapper("/order?buy");
        var refreshUpdate = Fixtures.answerWrapper(2, 100, "book#refreshContext");
        var rebuilt = CommandContext.create(command).addUpdate(refreshUpdate);

        assertThat(rebuilt.getCommand()).isEqualTo("order");
        assertThat(rebuilt.getAnswers()).containsExactly("buy", "book");
        assertThat(rebuilt.getDynamicParams().needRefreshContext()).isTrue();
    }

    @Test
    @DisplayName("getDynamicParams reads the current update, falling back to the command update")
    void dynamicParamsFallback() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order#approved"));
        assertThat(context.getDynamicParams().commandApproved()).isTrue();

        context.addUpdate(Fixtures.answerWrapper(2, 100, "book#initiator:alice"));
        assertThat(context.getDynamicParams().getInitiator()).isEqualTo("alice");
    }

    @Test
    @DisplayName("addQuestionMessage rejects null and records the message")
    void questionMessages() {
        var context = CommandContext.create(Fixtures.commandWrapper("/order"));
        assertThatThrownBy(() -> context.addQuestionMessage(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("Message is null");
        var msg = Fixtures.message(Fixtures.CHAT_ID, 555, "question");
        context.addQuestionMessage(msg);
        assertThat(context.getQuestionMessages()).containsExactly(msg);
    }
}