package com.kb.sessionbot;

import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;

import java.io.Serializable;

/**
 * Executes a single outbound Telegram method and returns its API result (e.g. the sent
 * {@link org.telegram.telegrambots.meta.api.objects.message.Message}). The likeliest
 * custom-override point for a consuming app, hence an interface.
 */
public interface MessageExecutor {
    <T extends Serializable> T execute(PartialBotApiMethod<T> message);
}