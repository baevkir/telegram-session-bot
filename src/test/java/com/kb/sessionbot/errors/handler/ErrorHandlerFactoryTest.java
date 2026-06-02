package com.kb.sessionbot.errors.handler;

import com.kb.sessionbot.errors.exception.BotAuthException;
import com.kb.sessionbot.errors.exception.BotCommandException;
import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.model.CommandContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlerFactoryTest {

    private CommandContext context;
    private ErrorHandlerFactory factory;

    @BeforeEach
    void setUp() {
        context = Fixtures.contextFor("/order");
        factory = new ErrorHandlerFactory(
            List.<ErrorHandler<?>>of(new BotCommandErrorHandler(), new BotAuthErrorHandler()));
        factory.init();
    }

    @Test
    @DisplayName("routes BotAuthException to its handler, emitting the exception message")
    void routesAuthException() {
        StepVerifier.create(factory.handle(new BotAuthException(context, "denied")))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).isEqualTo("denied");
                assertThat(((SendMessage) m).getChatId()).isEqualTo(String.valueOf(Fixtures.CHAT_ID));
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("walks the cause chain root-outward and routes BotCommandException to its handler (root-cause message)")
    void routesCommandExceptionUsingRootCause() {
        // getThrowableList = [BotCommandException, IllegalStateException]; reversed walk checks the
        // root (IllegalStateException, no handler) first, then matches the outer BotCommandException.
        var exception = new BotCommandException(context, new IllegalStateException("boom"));
        StepVerifier.create(factory.handle(exception))
            .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("boom"))
            .verifyComplete();
    }

    @Test
    @DisplayName("a root-cause message containing '{}' is emitted verbatim (constant log template)")
    void rootCauseWithBracesIsEmittedVerbatim() {
        var msg = "bad value {} not allowed";
        var ex = new BotCommandException(context, new IllegalStateException(msg));
        StepVerifier.create(factory.handle(ex))
            .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo(msg))
            .verifyComplete();
    }

    @Test
    @DisplayName("unhandled exception type yields empty (swallowed)")
    void noHandlerYieldsEmpty() {
        StepVerifier.create(factory.handle(new IllegalStateException("unmapped")))
            .verifyComplete();
    }

    abstract static class BaseCommandHandler implements ErrorHandler<BotCommandException> { }

    static class SubclassedCommandHandler extends BaseCommandHandler {
        @Override
        public reactor.core.publisher.Mono<? extends org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod<?>> handle(BotCommandException exception) {
            return reactor.core.publisher.Mono.fromSupplier(() ->
                SendMessage.builder().chatId(exception.getContext().getChatId()).text("handled-by-subclass").build());
        }
    }

    @Test
    @DisplayName("resolves the exception type from a handler that declares ErrorHandler on a superclass")
    void resolvesTypeArgumentThroughSuperclass() {
        var factory = new ErrorHandlerFactory(List.<ErrorHandler<?>>of(new SubclassedCommandHandler()));
        factory.init();
        var ex = new BotCommandException(context, new IllegalStateException("boom"));
        StepVerifier.create(factory.handle(ex))
            .assertNext(m -> assertThat(((SendMessage) m).getText()).isEqualTo("handled-by-subclass"))
            .verifyComplete();
    }
}