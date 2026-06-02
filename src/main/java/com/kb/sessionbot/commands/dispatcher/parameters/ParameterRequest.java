package com.kb.sessionbot.commands.dispatcher.parameters;

import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.Option;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ParameterRequest {
    private final CommandContext context;
    private final Integer index;
    private final String text;
    private final Class<?> parameterType;
    private final boolean required;
    private final List<Option> options;
}
