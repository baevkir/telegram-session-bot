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
     * Get the description of this command, localized for the given user.
     *
     * @param userName the Telegram user name to resolve the language for; {@code null} for the
     *                 bot-wide/configured language (e.g. the startup command list, which has no user)
     * @return the description as String
     */
    String getDescription(String userName);

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
