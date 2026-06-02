package com.kb.sessionbot.commands;

import com.kb.sessionbot.model.MessageDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CommandBuilderTest {

    @Nested
    @DisplayName("build()")
    class Build {

        @Test
        void commandOnly() {
            assertThat(CommandBuilder.create().command("order").build()).isEqualTo("/order");
        }

        @Test
        void commandWithAnswers() {
            assertThat(CommandBuilder.create().command("order").addAnswer("buy").addAnswer("book").build())
                .isEqualTo("/order?buy&book");
        }

        @Test
        void answersOnlyWithoutCommand() {
            assertThat(CommandBuilder.create().addAnswer("buy").addAnswer("book").build())
                .isEqualTo("buy&book");
        }

        @Test
        void typedAnswerOverloads() {
            assertThat(CommandBuilder.create().command("c").addAnswer(5L).addAnswer(true).build())
                .isEqualTo("/c?5&true");
            assertThat(CommandBuilder.create().command("c").addAnswer(LocalDate.of(2026, 6, 2)).build())
                .isEqualTo("/c?2026-06-02");
        }

        @Test
        void singleDynamicParamWithValue() {
            assertThat(CommandBuilder.create().command("c").addParam("k", "v").build())
                .isEqualTo("/c#k:v");
        }

        @Test
        void singleDynamicParamWithoutValueEmitsKeyOnly() {
            assertThat(CommandBuilder.create().command("c").addParam("flag").build())
                .isEqualTo("/c#flag");
        }

        @Test
        void refreshContextFlag() {
            assertThat(CommandBuilder.create().refreshContext().build()).isEqualTo("#refreshContext");
        }

        @Test
        void scipAnswerCarriesIndex() {
            assertThat(CommandBuilder.create().scipAnswer(3).build()).isEqualTo("#scipAnswer:3");
        }

        @Test
        void commandApprovedFlag() {
            assertThat(CommandBuilder.create().commandApproved().build()).isEqualTo("#approved");
        }

        @Test
        void setInitiatorCarriesName() {
            assertThat(CommandBuilder.create().setInitiator("alice").build()).isEqualTo("#initiator:alice");
        }

        @Test
        void combinedCommandAnswersAndSingleParam() {
            assertThat(CommandBuilder.create().command("order").addAnswer("buy").addParam("k", "v").build())
                .isEqualTo("/order?buy#k:v");
        }
    }

    @Nested
    @DisplayName("round-trip with MessageDescriptor")
    class RoundTrip {

        @Test
        void commandAnswersAndParamsSurviveParse() {
            var wire = CommandBuilder.create()
                .command("order")
                .addAnswer("buy")
                .addAnswer("book")
                .addParam("first", "1")
                .addParam("second", "2")
                .build();

            var descriptor = MessageDescriptor.parse(wire);

            assertThat(descriptor.isCommand()).isTrue();
            assertThat(descriptor.getCommand()).isEqualTo("order");
            assertThat(descriptor.getAnswers()).containsExactly("buy", "book");
            assertThat(descriptor.getDynamicParams().getParams())
                .containsEntry("first", "1")
                .containsEntry("second", "2")
                .hasSize(2);
        }

        @Test
        void answersOnlyRoundTrip() {
            var wire = CommandBuilder.create().addAnswer("a").addAnswer("b").build();
            var descriptor = MessageDescriptor.parse(wire);
            assertThat(descriptor.isCommand()).isFalse();
            assertThat(descriptor.getAnswers()).containsExactly("a", "b");
        }

        @Test
        void flagParamRoundTripsToEmptyValue() {
            var wire = CommandBuilder.create().refreshContext().build();
            var descriptor = MessageDescriptor.parse(wire);
            assertThat(descriptor.getDynamicParams().needRefreshContext()).isTrue();
            assertThat(descriptor.getDynamicParams().getParams()).containsEntry("refreshContext", "");
        }
    }

    @Nested
    @DisplayName("64-byte boundary")
    class ByteBoundary {

        @Test
        void atOrUnderLimitProducesExactString() {
            var answer = "x".repeat(61);
            var wire = CommandBuilder.create().command("c").addAnswer(answer).build();
            assertThat(wire).isEqualTo("/c?" + answer);
            assertThat(wire.getBytes().length).isEqualTo(64);
        }

        @Test
        void overLimitStillBuildsTheFullString() {
            var answer = "x".repeat(62);
            var wire = CommandBuilder.create().command("c").addAnswer(answer).build();
            assertThat(wire.getBytes().length).isEqualTo(65);
            assertThat(wire).isEqualTo("/c?" + answer);
        }
    }
}