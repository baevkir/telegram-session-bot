package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.commands.dispatcher.annotations.BotCommand;
import com.kb.sessionbot.commands.dispatcher.annotations.CommandMethod;
import com.kb.sessionbot.model.CommandContext;
import com.kb.sessionbot.model.DynamicParameters;
import com.kb.sessionbot.model.UpdateWrapper;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

@BotCommand(value = "echo", description = "Echo fixture")
public class EchoCommand {

    @CommandMethod
    public SendMessage defaultMethod() {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("default").build();
    }

    @CommandMethod(arguments = "wrapCommand")
    public SendMessage wrapCommand(UpdateWrapper command) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("command:" + command.getCommand()).build();
    }

    @CommandMethod(arguments = "wrapUpdate")
    public SendMessage wrapUpdate(UpdateWrapper update) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("update-wrapper:" + (update == null)).build();
    }

    @CommandMethod(arguments = "rawUpdate")
    public SendMessage rawUpdate(Update update) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("raw-update:" + (update == null)).build();
    }

    @CommandMethod(arguments = "fromUser")
    public SendMessage fromUser(User from) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("from:" + (from == null ? "null" : from.getUserName())).build();
    }

    @CommandMethod(arguments = "chat")
    public SendMessage chat(String chatId) {
        return SendMessage.builder().chatId(chatId).text("chat:" + chatId).build();
    }

    @CommandMethod(arguments = "dyn")
    public SendMessage dyn(DynamicParameters params) {
        return SendMessage.builder().chatId(Fixtures.CHAT_ID + "").text("dyn:" + params.commandApproved()).build();
    }

    @CommandMethod(arguments = "ctx")
    public SendMessage ctx(CommandContext context) {
        return SendMessage.builder().chatId(context.getChatId()).text("ctx:" + context.getCommand()).build();
    }
}