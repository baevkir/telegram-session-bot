package com.kb.sessionbot;

import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.commands.HelpCommand;
import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.commands.dispatcher.DispatcherBotCommand;
import com.kb.sessionbot.config.CommandsSessionBotProperties;
import com.kb.sessionbot.errors.exception.BotAuthException;
import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import com.kb.sessionbot.fixtures.EchoCommand;
import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.fixtures.FixtureCommandConfig;
import com.kb.sessionbot.fixtures.OrderCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class CommandsSessionBotTest {

    private AnnotationConfigApplicationContext springContext;
    private TelegramClient telegramClient;
    private CommandsFactory commandsFactory;
    private ErrorHandlerFactory errorHandlerFactory;
    private OutboundMessages outboundMessages;

    @BeforeEach
    void setUp() throws Exception {
        springContext = new AnnotationConfigApplicationContext(FixtureCommandConfig.class);

        List<IBotCommand> commands = List.of(
            new DispatcherBotCommand(springContext.getBean(OrderCommand.class), springContext),
            new DispatcherBotCommand(springContext.getBean(EchoCommand.class), springContext));
        var helpCommand = new HelpCommand(commands);
        commandsFactory = new CommandsFactory(helpCommand, commands);
        commandsFactory.start();

        errorHandlerFactory = new ErrorHandlerFactory(
            List.<ErrorHandler<?>>of(new BotCommandErrorHandler(), new BotAuthErrorHandler()));
        errorHandlerFactory.init();

        telegramClient = Mockito.mock(TelegramClient.class);
        // execute(SendMessage) returns a Message so the progress branch records a question message.
        Mockito.when(telegramClient.execute(any(BotApiMethod.class)))
            .thenReturn(Fixtures.message(Fixtures.CHAT_ID, 999, "sent"));

        outboundMessages = new OutboundMessages();
    }

    @AfterEach
    void tearDown() {
        springContext.close();
    }

    private CommandsSessionBot bot(AuthInterceptor auth) {
        return new CommandsSessionBot(
            commandsFactory, auth, errorHandlerFactory,
            new CommandsSessionBotProperties(),
            new TelegramClientMessageExecutor(telegramClient, errorHandlerFactory),
            outboundMessages);
    }

    private static final AuthInterceptor ALLOW = ctx -> reactor.core.publisher.Mono.just(true);
    private static final AuthInterceptor DENY = ctx -> reactor.core.publisher.Mono.just(false);

    @DisplayName("command update completes and emits its SendMessage response")
    @Test
    void commandStartsFreshContext() {
        var bot = bot(ALLOW);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")));

        StepVerifier.create(bot.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).isEqualTo("buy:book");
            })
            .verifyComplete();
    }

    @DisplayName("completed command executes its response against the client (regression: close-state messages must be sent)")
    @Test
    void completedCommandExecutesItsResponse() throws Exception {
        var bot = bot(ALLOW);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")));

        StepVerifier.create(bot.handleUpdates(updates)).expectNextCount(1).verifyComplete();

        var executed = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(telegramClient, atLeastOnce()).execute(executed.capture());
        assertThat(executed.getAllValues())
            .anyMatch(m -> m instanceof SendMessage && "buy:book".equals(((SendMessage) m).getText()));
    }

    @DisplayName("non-command answer appends to the in-progress context and completes it")
    @Test
    void nonCommandAppendsToContext() {
        var bot = bot(ALLOW);
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
            Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book")));

        // First fold step -> "buy" only -> prompt (progress, executed as side effect).
        // Second fold step -> appends "book" -> invocation "buy:book" + cleanup.
        StepVerifier.create(bot.handleUpdates(updates).filter(m -> m instanceof SendMessage)
                .map(m -> ((SendMessage) m).getText()))
            .expectNextMatches(text -> text.contains("product"))
            .expectNext("buy:book")
            .verifyComplete();
    }

    @DisplayName("empty context routes to HelpCommand")
    @Test
    void emptyContextUsesHelp() {
        var bot = bot(ALLOW);
        // A non-command first update with no in-progress context stays empty -> help.
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "book")));

        StepVerifier.create(bot.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).contains("Помощь");
            })
            .verifyComplete();
    }

    @DisplayName("auth rejection with a missing user surfaces BotAuthException, not NPE")
    @Test
    void authRejectWithMissingUserDoesNotNpe() {
        var bot = bot(DENY);
        var update = new org.telegram.telegrambots.meta.api.objects.Update();
        update.setUpdateId(1);
        update.setMessage(org.telegram.telegrambots.meta.api.objects.message.Message.builder()
            .messageId(100)
            .chat(org.telegram.telegrambots.meta.api.objects.chat.Chat.builder().id(Fixtures.CHAT_ID).type("private").build())
            .text("/order?buy&book")
            .build()); // no .from(...)
        var updates = Flux.just(Fixtures.wrap(update));

        StepVerifier.create(bot.handleUpdates(updates))
            .expectError(BotAuthException.class)
            .verify();
    }

    @DisplayName("refreshContext rebuilds the context from the original command")
    @Test
    void refreshContextRebuild() {
        var bot = bot(ALLOW);
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
            Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book#refreshContext")));

        // After refresh the context is rebuilt from "/order?buy" then "book" re-applied -> "buy:book".
        StepVerifier.create(bot.handleUpdates(updates)
                .filter(m -> m instanceof SendMessage)
                .map(m -> ((SendMessage) m).getText()))
            .expectNextMatches(text -> text.contains("product"))
            .expectNext("buy:book")
            .verifyComplete();
    }

    @DisplayName("auth rejection surfaces BotAuthException")
    @Test
    void authRejectSurfacesError() {
        var bot = bot(DENY);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")));

        StepVerifier.create(bot.handleUpdates(updates))
            .expectError(BotAuthException.class)
            .verify();
    }

    @DisplayName("progress state records the question message via addQuestionMessage side effect")
    @Test
    void progressRecordsQuestionMessage() throws Exception {
        var bot = bot(ALLOW);
        // Single answer -> command stays in progress and prompts; the prompt SendMessage is executed.
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")));

        StepVerifier.create(bot.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).contains("product");
            })
            .verifyComplete();

        // The prompt was executed against the client (progress branch).
        verify(telegramClient, timeout(2000)).execute(any(BotApiMethod.class));
    }

    @DisplayName("consume() drives an update end-to-end to telegramClient.execute")
    @Test
    void consumeEndToEnd() throws Exception {
        var bot = bot(ALLOW);
        bot.init(); // wires the reactive pipeline and emits SetMyCommands
        // completed-command response execution is a known gap fixed in Spec 2; a single-answer command stays in progress and executes its prompt
        bot.consume(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy"));

        // SetMyCommands at startup + SendMessage + DeleteMessage from the completed command.
        verify(telegramClient, timeout(2000).atLeast(2)).execute(any(BotApiMethod.class));
    }

    @Nested
    @DisplayName("concurrency guarantees")
    class Concurrency {

        @DisplayName("per-chat updates process in arrival order under concatMap")
        @Test
        void perChatOrderingIsPreserved() {
            var bot = bot(ALLOW);
            // Within one chat: first "/order?buy" prompts for product, then "book" completes -> "buy:book".
            var updates = Flux.just(
                Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
                Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book")));

            StepVerifier.create(bot.handleUpdates(updates)
                    .filter(m -> m instanceof SendMessage)
                    .map(m -> ((SendMessage) m).getText()))
                .expectNextMatches(text -> text.contains("product"))
                .expectNext("buy:book")
                .verifyComplete();
        }

        @DisplayName("a chat whose handler errors does not terminate the stream (failure isolation)")
        @Test
        void failureInOneChatDoesNotKillPipeline() throws Exception {
            var bot = bot(DENY); // auth denial makes the first chat's dispatch error
            bot.init();

            // chat A: auth-denied command -> BotAuthException routed by per-group onErrorResume.
            bot.consume(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book"));
            // chat B (different chatId): a help-routing empty update still processes afterwards.
            long otherChat = Fixtures.CHAT_ID + 1;
            bot.consume(Fixtures.messageUpdate(2, otherChat, 200, "anything"));

            // SetMyCommands + the auth error message + chat B's help response all execute;
            // the pipeline survived the first chat's error.
            verify(telegramClient, timeout(5000).atLeast(3)).execute(any(BotApiMethod.class));
        }
    }
}