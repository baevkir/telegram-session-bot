package com.kb.sessionbot.commands;

import com.kb.sessionbot.model.CommandContext;
import org.reactivestreams.Publisher;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;


public interface IBotCommand {
    /**
     * Get the identifier of this command
     *
     * @return the identifier
     */
    String getCommandIdentifier();

    /**
     * Get the description of this command (bot-wide, e.g. for the startup command list where no user
     * context is available).
     *
     * @return the description as String
     */
    String getDescription();

    /**
     * Get the description of this command for a specific conversation context, allowing per-user
     * localization. Defaults to {@link #getDescription()}.
     *
     * @param context the current command context (may carry the sender used to resolve a locale)
     * @return the description as String
     */
    default String getDescription(CommandContext context) {
        return getDescription();
    }

    /**
     * @return the true if bot command should not show in help
     */
    default boolean hidden() {
        return false;
    }

    /**
     * Process the message
     * @return
     * @param commandContext
     */
    Publisher<? extends PartialBotApiMethod<?>> process(CommandContext commandContext);
}
