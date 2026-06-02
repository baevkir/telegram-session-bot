package com.kb.sessionbot.commands.dispatcher;

import com.kb.sessionbot.fixtures.EchoCommand;
import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.fixtures.FixtureCommandConfig;
import com.kb.sessionbot.fixtures.OrderCommand;
import com.kb.sessionbot.model.CommandContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class CommandsDispatcherTest {

    private AnnotationConfigApplicationContext context;
    private CommandsDispatcher orderDispatcher;
    private CommandsDispatcher echoDispatcher;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(FixtureCommandConfig.class);
        orderDispatcher = new CommandsDispatcher(context.getBean(OrderCommand.class), context);
        echoDispatcher = new CommandsDispatcher(context.getBean(EchoCommand.class), context);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static CommandContext ctx(String wire) {
        return CommandContext.create(Fixtures.commandWrapper(wire));
    }

    @Nested
    @DisplayName("Jackson parameter binding")
    class Binding {

        @Test
        void bindsString() {
            var result = orderDispatcher.invoke(ctx("/order?buy&book"));
            assertThat(result.hasErrors()).isFalse();
            assertThat(result.getInvocationArgument()).isNull();
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("buy:book"))
                .verifyComplete();
        }

        @Test
        void bindsLong() {
            var result = orderDispatcher.invoke(ctx("/order?qty&5"));
            assertThat(result.hasErrors()).isFalse();
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("qty:5"))
                .verifyComplete();
        }

        @Test
        void bindsLocalDateViaJavaTimeModule() {
            var result = orderDispatcher.invoke(ctx("/order?schedule&2026-06-02"));
            assertThat(result.hasErrors()).isFalse();
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("date:2026-06-02"))
                .verifyComplete();
        }
    }

    @Nested
    @DisplayName("optional parameter handling")
    class Optional {

        @Test
        void missingRequiredRendersPrompt() {
            // "note&{required}&{optional}" with no required answer -> renderer prompt, no invocation.
            var result = orderDispatcher.invoke(ctx("/order?note"));
            assertThat(result.getInvocation()).isNull();
            assertThat(result.getInvocationArgument()).isNotNull();
            StepVerifier.create(result.getInvocationArgument())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).contains("required"))
                .verifyComplete();
        }

        @Test
        void optionalSkippedWhenScipAnswerSet() {
            // required supplied; optional missing but scipAnswer allows skipping index 2.
            var context = com.kb.sessionbot.model.CommandContext
                .create(Fixtures.commandWrapper("/order?note&hello"))
                .addUpdate(Fixtures.answerWrapper(2, 100, "#scipAnswer:2"));
            var result = orderDispatcher.invoke(context);
            assertThat(result.hasErrors()).isFalse();
            assertThat(result.getInvocationArgument()).isNull();
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("note:hello/null"))
                .verifyComplete();
        }
    }

    @Nested
    @DisplayName("auto-injection")
    class AutoInjection {

        @Test
        void updateWrapperCommand() {
            var result = echoDispatcher.invoke(ctx("/echo?wrapCommand"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("command:echo"))
                .verifyComplete();
        }

        @Test
        void updateWrapperUpdateIsNullWithoutCurrentUpdate() {
            // No follow-up update added, so current update is absent -> null UpdateWrapper.
            var result = echoDispatcher.invoke(ctx("/echo?wrapUpdate"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("update-wrapper:true"))
                .verifyComplete();
        }

        @Test
        void rawUpdateIsNullWithoutCurrentUpdate() {
            var result = echoDispatcher.invoke(ctx("/echo?rawUpdate"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("raw-update:true"))
                .verifyComplete();
        }

        @Test
        void userFrom() {
            var result = echoDispatcher.invoke(ctx("/echo?fromUser"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("from:tester"))
                .verifyComplete();
        }

        @Test
        void chatIdString() {
            var result = echoDispatcher.invoke(ctx("/echo?chat"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("chat:" + Fixtures.CHAT_ID))
                .verifyComplete();
        }

        @Test
        void dynamicParameters() {
            var result = echoDispatcher.invoke(ctx("/echo?dyn#approved"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("dyn:true"))
                .verifyComplete();
        }

        @Test
        void commandContext() {
            var result = echoDispatcher.invoke(ctx("/echo?ctx"));
            StepVerifier.create(result.getInvocation())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("ctx:echo"))
                .verifyComplete();
        }
    }

    @Nested
    @DisplayName("no method match")
    class NoMatch {

        @Test
        void rendersDefaultPromptAndNoInvocation() {
            var result = orderDispatcher.invoke(ctx("/order?unsupported"));
            assertThat(result.getInvocation()).isNull();
            assertThat(result.getInvocationArgument()).isNotNull();
            StepVerifier.create(result.getInvocationArgument())
                .assertNext(m -> assertThat(((SendMessage) m).getText()).contains("order"))
                .verifyComplete();
        }
    }

    @Nested
    @DisplayName("invocation errors route to BotCommandException")
    class ErrorRouting {

        @Test
        void invocationErrorIsWrappedAsBotCommandException() {
            // qty binds a Long; a non-numeric answer makes Jackson conversion fail synchronously
            // inside invoke(), which the catch block wraps as a BotCommandException carrying the context.
            var result = orderDispatcher.invoke(ctx("/order?qty&not-a-number"));
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getInvocationError())
                .isInstanceOf(com.kb.sessionbot.errors.exception.BotCommandException.class);
        }

        @Test
        void routedThroughErrorHandlerFactoryProducesSendMessage() {
            var factory = new com.kb.sessionbot.errors.handler.ErrorHandlerFactory(
                java.util.List.<com.kb.sessionbot.errors.handler.ErrorHandler<?>>of(
                    new com.kb.sessionbot.errors.handler.BotCommandErrorHandler(),
                    new com.kb.sessionbot.errors.handler.BotAuthErrorHandler()));
            factory.init();
            var ex = new com.kb.sessionbot.errors.exception.BotCommandException(
                ctx("/order?unsupported"),
                new IllegalStateException("Cannot find command method for order with arguments [unsupported]"));
            StepVerifier.create(factory.handle(ex))
                .assertNext(m -> assertThat(m).isInstanceOf(SendMessage.class))
                .verifyComplete();
        }
    }
}