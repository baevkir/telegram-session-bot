package com.kb.sessionbot.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicParametersTest {

    @Test
    @DisplayName("empty() has no params and reports false for every flag")
    void emptyHasNoParams() {
        var params = DynamicParameters.empty();
        assertThat(params.isEmpty()).isTrue();
        assertThat(params.needRefreshContext()).isFalse();
        assertThat(params.commandApproved()).isFalse();
        assertThat(params.canScipAnswer(0)).isFalse();
        assertThat(params.getInitiator()).isNull();
    }

    @Nested
    @DisplayName("canScipAnswer")
    class CanScip {

        @Test
        void falseWhenScipKeyAbsent() {
            assertThat(DynamicParameters.create(Map.of("other", "1")).canScipAnswer(0)).isFalse();
        }

        @ParameterizedTest(name = "scipAnswer={0}, query index={1} -> {2}")
        @CsvSource({
            "2, 0, true",
            "2, 1, true",
            "2, 2, true",
            "2, 3, false",
            "0, 0, true",
            "0, 1, false"
        })
        void allowsSkipWhenAllowedIndexAtLeastQueried(String allowed, int index, boolean expected) {
            var params = DynamicParameters.create(Map.of("scipAnswer", allowed));
            assertThat(params.canScipAnswer(index)).isEqualTo(expected);
        }
    }

    @Test
    void needRefreshContextIsKeyPresence() {
        assertThat(DynamicParameters.create(Map.of("refreshContext", "")).needRefreshContext()).isTrue();
        assertThat(DynamicParameters.create(Map.of("x", "y")).needRefreshContext()).isFalse();
    }

    @Test
    void commandApprovedIsKeyPresence() {
        assertThat(DynamicParameters.create(Map.of("approved", "")).commandApproved()).isTrue();
        assertThat(DynamicParameters.create(Map.of("x", "y")).commandApproved()).isFalse();
    }

    @Test
    void getInitiatorReturnsRawValueOrNull() {
        assertThat(DynamicParameters.create(Map.of("initiator", "alice")).getInitiator()).isEqualTo("alice");
        assertThat(DynamicParameters.create(Map.of("x", "y")).getInitiator()).isNull();
    }

    @Test
    void hasParamAndGetParam() {
        var params = DynamicParameters.create(Map.of("k", "v"));
        assertThat(params.hasParam("k")).isTrue();
        assertThat(params.hasParam("missing")).isFalse();
        assertThat(params.getParam("k")).isEqualTo("v");
        assertThat(params.getParam("missing")).isNull();
    }
}