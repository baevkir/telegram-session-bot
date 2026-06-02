package com.kb.sessionbot.commands;

import com.kb.sessionbot.model.MessageDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageDescriptorTest {

    @Nested
    @DisplayName("command detection")
    class CommandDetection {

        @ParameterizedTest(name = "[{index}] \"{0}\" -> command={1}")
        @CsvSource({
            "/order,             true,  order",
            "/order?buy&book,    true,  order",
            "/order#k:v,         true,  order",
            "/order?buy#k:v,     true,  order",
            "order,              false, ",
            "buy&book,           false, ",
            "#k:v,               false, "
        })
        void parsesCommandFlagAndName(String text, boolean isCommand, String expectedCommand) {
            var descriptor = MessageDescriptor.parse(text);
            assertThat(descriptor.isCommand()).isEqualTo(isCommand);
            assertThat(descriptor.getCommand()).isEqualTo(expectedCommand);
        }
    }

    @Nested
    @DisplayName("answers parsing")
    class Answers {

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @CsvSource({
            "/order,            0",
            "/order?,           0",
            "/order?buy,        1",
            "/order?buy&book,   2",
            "/order#k:v,        0",
            "buy&book,          2",
            "#k:v,              0"
        })
        void answerCount(String text, int expectedCount) {
            assertThat(MessageDescriptor.parse(text).getAnswers()).hasSize(expectedCount);
        }

        @Test
        void commandWithAnswersAndParams() {
            var descriptor = MessageDescriptor.parse("/order?buy&book#k:v");
            assertThat(descriptor.getAnswers()).containsExactly("buy", "book");
        }

        @Test
        void answersOnlyWithoutLeadingSlash() {
            var descriptor = MessageDescriptor.parse("buy&book");
            assertThat(descriptor.isCommand()).isFalse();
            assertThat(descriptor.getAnswers()).containsExactly("buy", "book");
        }

        @Test
        void trailingArgumentSeparatorYieldsNoAnswers() {
            // "/order?".split("\\?") -> ["/order"], length 1 -> empty answers.
            assertThat(MessageDescriptor.parse("/order?").getAnswers()).isEmpty();
        }
    }

    @Nested
    @DisplayName("dynamic params parsing")
    class DynamicParams {

        @Test
        void noParamsYieldsEmptyMap() {
            assertThat(MessageDescriptor.parse("/order?buy").getDynamicParams().getParams()).isEmpty();
        }

        @Test
        void singleParamWithValue() {
            assertThat(MessageDescriptor.parse("/order#k:v").getDynamicParams().getParams())
                .containsExactlyEntriesOf(java.util.Map.of("k", "v"));
        }

        @Test
        void paramWithoutValueBecomesEmptyString() {
            assertThat(MessageDescriptor.parse("/order#refreshContext").getDynamicParams().getParams())
                .containsEntry("refreshContext", "");
        }

        @Test
        void multipleDynamicParams() {
            assertThat(MessageDescriptor.parse("/order#a:1&b:2&flag").getDynamicParams().getParams())
                .containsEntry("a", "1")
                .containsEntry("b", "2")
                .containsEntry("flag", "")
                .hasSize(3);
        }

        @Test
        void paramsOnlyWithoutCommandOrAnswers() {
            var descriptor = MessageDescriptor.parse("#k:v");
            assertThat(descriptor.isCommand()).isFalse();
            assertThat(descriptor.getCommand()).isNull();
            assertThat(descriptor.getAnswers()).isEmpty();
            assertThat(descriptor.getDynamicParams().getParams()).containsEntry("k", "v");
        }
    }

    @Nested
    @DisplayName("guard cases")
    class Guards {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   "})
        void blankInputThrows(String text) {
            assertThatThrownBy(() -> MessageDescriptor.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("text is empty");
        }
    }
}