package com.kb.sessionbot.commands;

import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Slf4j
public class CommandsFactory {
    private final HelpCommand helpCommand;
    private final List<IBotCommand> botCommands;

    private final Map<String, IBotCommand> commandRegistryMap = new HashMap<>();

    public CommandsFactory(HelpCommand helpCommand, List<IBotCommand> botCommands) {
        this.helpCommand = helpCommand;
        this.botCommands = botCommands;
    }

    public final IBotCommand getHelpCommand() {
        return helpCommand;
    }

    public final IBotCommand getCommand(String commandName) {
        return commandRegistryMap.getOrDefault(commandName, helpCommand);
    }

    public final List<IBotCommand> getCommands() {
        var commands = new ArrayList<IBotCommand>();
        commands.add(helpCommand);
        commands.addAll(botCommands);
        return Collections.unmodifiableList(commands);
    }

    @PostConstruct
    public void start() {
        botCommands.forEach(command -> commandRegistryMap.put(command.getCommandIdentifier(), command));
    }
}
