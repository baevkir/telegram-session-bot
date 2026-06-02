package com.kb.sessionbot.commands.dispatcher.parameters;

import org.reactivestreams.Publisher;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;

/**
 * Produces the message that prompts the user for a single missing command argument.
 */
public interface ParameterRenderer {
    Publisher<? extends PartialBotApiMethod<?>> render(ParameterRequest parameterRequest);
}
