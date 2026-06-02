package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.commands.dispatcher.annotations.BotCommand;
import com.kb.sessionbot.commands.dispatcher.annotations.CommandMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.LocalDate;

@BotCommand(value = "badinject", description = "Unsupported auto-injection fixture")
public class BadInjectionCommand {

    // 'when' is neither @Parameter-annotated nor a supported auto-injection type (Update/UpdateWrapper/User/String chatId/DynamicParameters/CommandContext).
    @CommandMethod(arguments = "go")
    public SendMessage go(LocalDate when) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("when:" + when).build();
    }
}