package com.kb.sessionbot.commands.presenter;

import com.kb.sessionbot.model.CommandContext;
import org.reactivestreams.Publisher;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

public interface BotMethodPresenter<S> {
    Publisher<BotApiMethod<?>> buildMessage(S source, CommandContext context);
    Publisher<BotApiMethod<?>> buildEditMessage(S source, CommandContext context, Integer messageId);
}
