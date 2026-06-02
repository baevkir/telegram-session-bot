package com.kb.sessionbot.commands.dispatcher.parameters;

import org.reactivestreams.Publisher;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;

public interface ParameterRenderer {
    Publisher<? extends PartialBotApiMethod<?>> render(ParameterRequest parameterRequest);
}
