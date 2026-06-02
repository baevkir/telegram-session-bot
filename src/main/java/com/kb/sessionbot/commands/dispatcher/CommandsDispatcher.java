package com.kb.sessionbot.commands.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kb.sessionbot.commands.CommandBuilder;
import com.kb.sessionbot.commands.dispatcher.annotations.BotCommand;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRequest;
import com.kb.sessionbot.errors.exception.BotCommandException;
import com.kb.sessionbot.model.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.context.ApplicationContext;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.*;


/**
 * Reflects over a {@code @BotCommand} bean's {@code @CommandMethod} methods, scores them
 * against the context's accumulated answers, binds each parameter, and either invokes the
 * best match or renders a prompt for the next missing argument.
 */
@Slf4j
public class CommandsDispatcher {
    private final Object command;
    @Getter private final String commandId;
    @Getter private final String commandDescription;
    @Getter private final boolean hidden;
    private final MethodMatcher methodMatcher;
    private final ObjectMapper mapper;
    private final ApplicationContext applicationContext;

    public CommandsDispatcher(Object command, ApplicationContext applicationContext) {
        this.command = command;
        var botCommand = command.getClass().getAnnotation(BotCommand.class);
        this.commandId = botCommand.value();
        this.commandDescription = botCommand.description();
        this.hidden = botCommand.hidden();
        this.methodMatcher = MethodMatcher.create(command);
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        this.applicationContext = applicationContext;
    }

    public InvocationResult invoke(CommandContext context) {
        var invocationResult = new InvocationResult();

        try {
            var methodDescriptor = findInvokerMethod(context, invocationResult);
            if (methodDescriptor == null) {
                if (invocationResult.invocationArgument != null) {
                    return invocationResult;
                }
                throw new RuntimeException(
                    "Cannot find command method for " + context.getCommand() + " with arguments " + context.getAnswers()
                );
            }
            invocationResult.invocationMethod = methodDescriptor.getMethod();

            var answers = context.getAnswers();
            var args = new ArrayList<>();
            for (ParameterDescriptor parameter : methodDescriptor.getParameters()) {
                if (invocationResult.hasErrors()) {
                    continue;
                }

                if (parameter.isAnnotated()) {
                    var index = methodDescriptor.getPlaceholderIndexes().get(parameter.getName());
                    Optional<?> argument = getArgument(answers, methodDescriptor, parameter);
                    if (argument.isPresent()) {
                        invocationResult.addArgument(argument.get());
                        args.add(argument.get());
                    } else {
                        if (!parameter.isRequired() && context.getCurrentUpdate().map(update -> update.getDynamicParams().canScipAnswer(index)).orElse(false)) {
                            invocationResult.addArgument(null);
                            args.add(null);
                        } else {
                            invocationResult.invocationArgument = getRenderer(parameter).render(
                                ParameterRequest.builder()
                                    .index(index)
                                    .text(String.format("Пожалуйста укажите поле '%s'.", parameter.getDisplayName()))
                                    .parameterType(parameter.getParameterType())
                                    .required(parameter.isRequired())
                                    .context(context)
                                    .options(parameter.getOptions())
                                    .build()
                            );
                            return invocationResult;
                        }
                    }
                    continue;
                }

                if (UpdateWrapper.class.equals(parameter.getParameterType()) && parameter.getName().equals("command")) {
                    args.add(context.getCommandUpdate());
                } else if (UpdateWrapper.class.equals(parameter.getParameterType()) && parameter.getName().equals("update")) {
                    args.add(context.getCurrentUpdate().orElse(null));
                } else if (Update.class.equals(parameter.getParameterType()) && parameter.getName().equals("update")) {
                    args.add(context.getCurrentUpdate().map(UpdateWrapper::getUpdate).orElse(null));
                } else if (User.class.equals(parameter.getParameterType()) && parameter.getName().equals("from")) {
                    args.add(context.getCommandUpdate().getFrom());
                } else if (String.class.equals(parameter.getParameterType()) && parameter.getName().equals("chatId")) {
                    args.add(context.getChatId());
                } else if (DynamicParameters.class.equals(parameter.getParameterType())) {
                    args.add(context.getDynamicParams());
                } else if (CommandContext.class.equals(parameter.getParameterType())) {
                    args.add(context);
                }

            }

            if (!invocationResult.hasErrors()) {
                invocationResult.invocation = Mono.fromSupplier(
                        () -> {
                            ReflectionUtils.makeAccessible(invocationResult.invocationMethod);
                            return ReflectionUtils.invokeMethod(
                                invocationResult.invocationMethod,
                                command,
                                args.toArray()
                            );
                        })
                    .flatMapMany(result -> InvocationResultResolver.of(result).resolve())
                    .onErrorMap(error -> new BotCommandException(context, error));
            }
        } catch (Throwable error) {
            invocationResult.invocationError = new BotCommandException(context, error);
        }

        return invocationResult;
    }

    private Optional<?> getArgument(List<String> answers, MethodDescriptor methodDescriptor, ParameterDescriptor parameter) {
        var argumentIndex = methodDescriptor.getPlaceholderIndexes().get(parameter.getName());
        Assert.notNull(
            argumentIndex,
            () -> String.format("Placeholder %s is not defined in %s", parameter.getName(), methodDescriptor.getArguments())
        );
        if (argumentIndex < answers.size()) {
            return Optional.ofNullable(answers.get(argumentIndex))
                .map(answer -> {
                    if (answer.equals("null")) {
                        return null;
                    }
                   return mapper.convertValue(answer, parameter.getParameterType());
                });
        }
        return Optional.empty();
    }

    private ParameterRenderer getDefaultRenderer() {
        return applicationContext.getBean("defaultParameterRenderer", ParameterRenderer.class);
    }

    private ParameterRenderer getRenderer(ParameterDescriptor parameter) {
        if (parameter.getRendererType() != ParameterRenderer.class) {
            return applicationContext.getBean(parameter.getRendererType());
        }
        return applicationContext.getBean(parameter.getRenderer(), ParameterRenderer.class);
    }

    private MethodDescriptor findInvokerMethod(CommandContext commandContext, InvocationResult invocationResult) {
        return methodMatcher.getMatchingMethod(commandContext).orElseGet(() -> {
            var options = CommandBuilder.create().addAnswers(commandContext.getAnswers()).build();
            invocationResult.invocationArgument = getDefaultRenderer().render(
                ParameterRequest.builder()
                    .context(commandContext)
                    .required(true)
                    .text(String.format("Опции %s не поддерживаются для команды %s", options, commandContext.getCommand()))
                    .parameterType(String.class)
                    .build()
            );
            return null;
        });
    }

    @Getter
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class InvocationResult {
        private Publisher<? extends PartialBotApiMethod<?>> invocation;
        private Publisher<? extends PartialBotApiMethod<?>> invocationArgument;
        private Method invocationMethod;
        private final List<Object> commandArguments = new ArrayList<>();
        private Throwable invocationError;

        public boolean hasErrors() {
            return invocationError != null;
        }

        private void addArgument(Object argument) {
            commandArguments.add(argument);
        }
    }

}
