package com.kb.sessionbot.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.util.Assert;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.*;

/**
 * Per-chat session state for a multi-step command: the originating command update, the
 * accumulated answers, the question messages sent back to the user, and the
 * open &rarr; progress &rarr; close lifecycle.
 */
@ToString
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommandContext {

    private UpdateWrapper commandUpdate;
    private final Deque<UpdateWrapper> updates = new LinkedList<>();
    private final List<String> answers = Collections.synchronizedList(new ArrayList<>());
    private final List<Message> questionMessages = Collections.synchronizedList(new ArrayList<>());
    private ContextState state;

    public static CommandContext create(UpdateWrapper commandUpdate) {
        Assert.isTrue(commandUpdate.isCommand(), "Context should be created only for command.");
        CommandContext context = new CommandContext();
        context.commandUpdate = commandUpdate;
        context.answers.addAll(commandUpdate.getAnswers());
        context.state = ContextState.open;
        return context;
    }

    public static CommandContext empty() {
        return new CommandContext();
    }

    public String getCommand() {
        return commandUpdate.getCommand();
    }

    public CommandContext startProgress() {
        state = ContextState.progress;
        return this;
    }

    public CommandContext close() {
        state = ContextState.close;
        return this;
    }

    public CommandContext addAnswer(String answer) {
        answers.add(answer);
        return this;
    }

    public CommandContext addUpdate(UpdateWrapper update) {
        Assert.isTrue(!update.isCommand(), "Command should create new context");
        updates.add(update);
        return this;
    }

    public CommandContext addQuestionMessage(Message message) {
        Objects.requireNonNull(message, "Message is null");
        questionMessages.add(message);
        return this;
    }

    public boolean isEmpty() {
        return commandUpdate == null;
    }

    public List<String> getPendingArguments() {
        return getCurrentUpdate()
            .map(UpdateWrapper::getAnswers)
            .orElse(Collections.emptyList());
    }

    public String getChatId() {
        return Optional.ofNullable(commandUpdate)
            .or(this::getCurrentUpdate)
            .map(UpdateWrapper::getChatId)
            .orElse(null);
    }

    public Optional<UpdateWrapper> getInitialUpdate() {
        return Optional.ofNullable(updates.peekFirst());
    }

    public Optional<UpdateWrapper> getCurrentUpdate() {
        return Optional.ofNullable(updates.peekLast());
    }

    public List<String> getAnswers() {
        var result = new ArrayList<String>();
        result.addAll(answers);
        result.addAll(getPendingArguments());
        return Collections.unmodifiableList(result);
    }

    public DynamicParameters getDynamicParams() {
        return getCurrentUpdate().orElse(commandUpdate).getDynamicParams();
    }

}
