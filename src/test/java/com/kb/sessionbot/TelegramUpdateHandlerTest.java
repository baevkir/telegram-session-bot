package com.kb.sessionbot;

import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.commands.HelpCommand;
import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.commands.dispatcher.DispatcherBotCommand;
import com.kb.sessionbot.documents.DocumentHandler;
import com.kb.sessionbot.errors.exception.BotAuthException;
import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import com.kb.sessionbot.fixtures.EchoCommand;
import com.kb.sessionbot.fixtures.Fixtures;
import com.kb.sessionbot.fixtures.FixtureCommandConfig;
import com.kb.sessionbot.fixtures.OrderCommand;
import com.kb.sessionbot.model.CommandContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class TelegramUpdateHandlerTest {

    private static final AuthInterceptor ALLOW = ctx -> Mono.just(true);
    private static final AuthInterceptor DENY = ctx -> Mono.just(false);

    private AnnotationConfigApplicationContext springContext;
    private TelegramClient telegramClient;
    private CommandsFactory commandsFactory;
    private ErrorHandlerFactory errorHandlerFactory;

    @BeforeEach
    void setUp() throws Exception {
        springContext = new AnnotationConfigApplicationContext(FixtureCommandConfig.class);

        List<IBotCommand> commands = List.of(
            new DispatcherBotCommand(springContext.getBean(OrderCommand.class), springContext),
            new DispatcherBotCommand(springContext.getBean(EchoCommand.class), springContext));
        var helpCommand = new HelpCommand(commands, testLabels());
        commandsFactory = new CommandsFactory(helpCommand, commands);
        commandsFactory.start();

        errorHandlerFactory = new ErrorHandlerFactory(
            List.<ErrorHandler<?>>of(new BotCommandErrorHandler(), new BotAuthErrorHandler()));
        errorHandlerFactory.init();

        telegramClient = Mockito.mock(TelegramClient.class);
        Mockito.when(telegramClient.execute(any(BotApiMethod.class)))
            .thenReturn(Fixtures.message(Fixtures.CHAT_ID, 999, "sent"));
    }

    @AfterEach
    void tearDown() {
        springContext.close();
    }

    private TelegramUpdateHandler handler(AuthInterceptor auth) {
        return handler(auth, List.of());
    }

    private TelegramUpdateHandler handler(AuthInterceptor auth, List<DocumentHandler> documentHandlers) {
        return new TelegramUpdateHandler(
            commandsFactory, auth,
            new TelegramClientMessageExecutor(telegramClient, errorHandlerFactory),
            documentHandlers);
    }

    @DisplayName("command update completes and emits its SendMessage response")
    @Test
    void commandStartsFreshContext() {
        var handler = handler(ALLOW);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")));

        StepVerifier.create(handler.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).isEqualTo("buy:book");
            })
            .verifyComplete();
    }

    @DisplayName("completed command executes its response against the client")
    @Test
    void completedCommandExecutesItsResponse() throws Exception {
        var handler = handler(ALLOW);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")));

        StepVerifier.create(handler.handleUpdates(updates)).expectNextCount(1).verifyComplete();

        var executed = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(telegramClient, atLeastOnce()).execute(executed.capture());
        assertThat(executed.getAllValues())
            .anyMatch(m -> m instanceof SendMessage && "buy:book".equals(((SendMessage) m).getText()));
    }

    @DisplayName("non-command answer appends to the in-progress context and completes it")
    @Test
    void nonCommandAppendsToContext() {
        var handler = handler(ALLOW);
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
            Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book")));

        StepVerifier.create(handler.handleUpdates(updates).filter(m -> m instanceof SendMessage)
                .map(m -> ((SendMessage) m).getText()))
            .expectNextMatches(text -> text.contains("product"))
            .expectNext("buy:book")
            .verifyComplete();
    }

    @DisplayName("per-chat updates process in arrival order under concatMap")
    @Test
    void perChatOrderingIsPreserved() {
        var handler = handler(ALLOW);
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
            Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book")));

        StepVerifier.create(handler.handleUpdates(updates)
                .filter(m -> m instanceof SendMessage)
                .map(m -> ((SendMessage) m).getText()))
            .expectNextMatches(text -> text.contains("product"))
            .expectNext("buy:book")
            .verifyComplete();
    }

    @DisplayName("empty context routes to HelpCommand")
    @Test
    void emptyContextUsesHelp() {
        var handler = handler(ALLOW);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "book")));

        StepVerifier.create(handler.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).contains("Help");
            })
            .verifyComplete();
    }

    @DisplayName("auth rejection with a missing user surfaces BotAuthException, not NPE")
    @Test
    void authRejectWithMissingUserDoesNotNpe() {
        var handler = handler(DENY);
        var update = new org.telegram.telegrambots.meta.api.objects.Update();
        update.setUpdateId(1);
        update.setMessage(org.telegram.telegrambots.meta.api.objects.message.Message.builder()
            .messageId(100)
            .chat(org.telegram.telegrambots.meta.api.objects.chat.Chat.builder().id(Fixtures.CHAT_ID).type("private").build())
            .text("/order?buy&book")
            .build()); // no .from(...)
        var updates = Flux.just(Fixtures.wrap(update));

        StepVerifier.create(handler.handleUpdates(updates))
            .expectError(BotAuthException.class)
            .verify();
    }

    @DisplayName("refreshContext rebuilds the context from the original command")
    @Test
    void refreshContextRebuild() {
        var handler = handler(ALLOW);
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")),
            Fixtures.wrap(Fixtures.callbackUpdate(2, Fixtures.CHAT_ID, 101, "book#refreshContext")));

        StepVerifier.create(handler.handleUpdates(updates)
                .filter(m -> m instanceof SendMessage)
                .map(m -> ((SendMessage) m).getText()))
            .expectNextMatches(text -> text.contains("product"))
            .expectNext("buy:book")
            .verifyComplete();
    }

    @DisplayName("auth rejection surfaces BotAuthException")
    @Test
    void authRejectSurfacesError() {
        var handler = handler(DENY);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")));

        StepVerifier.create(handler.handleUpdates(updates))
            .expectError(BotAuthException.class)
            .verify();
    }

    @DisplayName("progress state records the question message via addQuestionMessage side effect")
    @Test
    void progressRecordsQuestionMessage() throws Exception {
        var handler = handler(ALLOW);
        var updates = Flux.just(Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")));

        StepVerifier.create(handler.handleUpdates(updates))
            .assertNext(m -> {
                assertThat(m).isInstanceOf(SendMessage.class);
                assertThat(((SendMessage) m).getText()).contains("product");
            })
            .verifyComplete();

        verify(telegramClient, timeout(2000)).execute(any(BotApiMethod.class));
    }

    private static com.kb.sessionbot.i18n.BotLabels testLabels() {
        var ms = new org.springframework.context.support.ResourceBundleMessageSource();
        ms.setBasenames("sessionbot-labels");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new com.kb.sessionbot.i18n.BotLabels(ms, new com.kb.sessionbot.i18n.ConfiguredLocaleProvider(java.util.Locale.ENGLISH));
    }

    @DisplayName("stream completes after a command closes; a following command is not processed by the same stream")
    @Test
    void completesAfterCommandClose() {
        var handler = handler(ALLOW);
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book")),
            Fixtures.wrap(Fixtures.messageUpdate(2, Fixtures.CHAT_ID, 101, "/order?buy&pen")));

        StepVerifier.create(handler.handleUpdates(updates)
                .filter(m -> m instanceof SendMessage)
                .map(m -> ((SendMessage) m).getText()))
            .expectNext("buy:book")   // first command's close result
            .verifyComplete();        // stream completed after the close -> "buy:pen" not processed here
    }

    @DisplayName("bare document routes to the matching document handler")
    @Test
    void documentRoutesToMatchingHandler() {
        DocumentHandler csvHandler = new DocumentHandler() {
            @Override public boolean supports(Document document) { return "data.csv".equals(document.getFileName()); }
            @Override public Publisher<PartialBotApiMethod<?>> handle(CommandContext context, Document document) {
                return Mono.just(SendMessage.builder().chatId(context.getChatId()).text("imported " + document.getFileName()).build());
            }
        };
        var handler = handler(ALLOW, List.of(csvHandler));
        var updates = Flux.just(Fixtures.wrap(Fixtures.documentUpdate(1, Fixtures.CHAT_ID, 100, "data.csv")));

        StepVerifier.create(handler.handleUpdates(updates))
            .assertNext(sent -> assertThat(((SendMessage) sent).getText()).isEqualTo("imported data.csv"))
            .verifyComplete();
    }

    @DisplayName("bare document with no matching handler falls back to help")
    @Test
    void documentWithoutHandlerFallsBackToHelp() {
        var handler = handler(ALLOW, List.of());
        var updates = Flux.just(Fixtures.wrap(Fixtures.documentUpdate(1, Fixtures.CHAT_ID, 100, "data.csv")));

        StepVerifier.create(handler.handleUpdates(updates))
            .assertNext(sent -> assertThat(sent).isInstanceOf(SendMessage.class))
            .verifyComplete();
    }

    @DisplayName("document during an active command flow never reaches document handlers")
    @Test
    void documentMidCommandDoesNotDispatchHandlers() {
        var invoked = new AtomicBoolean(false);
        DocumentHandler spyHandler = new DocumentHandler() {
            @Override public boolean supports(Document document) { invoked.set(true); return true; }
            @Override public Publisher<PartialBotApiMethod<?>> handle(CommandContext context, Document document) {
                invoked.set(true); return Mono.empty();
            }
        };
        var handler = handler(ALLOW, List.of(spyHandler));
        var updates = Flux.just(
            Fixtures.wrap(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order")),
            Fixtures.wrap(Fixtures.documentUpdate(2, Fixtures.CHAT_ID, 101, "data.csv")));

        StepVerifier.create(handler.handleUpdates(updates))
            .thenConsumeWhile(message -> true)
            .verifyComplete();
        assertThat(invoked).isFalse();
    }

    @DisplayName("document dispatch is auth-gated")
    @Test
    void documentDeniedByAuthErrors() {
        DocumentHandler csvHandler = new DocumentHandler() {
            @Override public boolean supports(Document document) { return true; }
            @Override public Publisher<PartialBotApiMethod<?>> handle(CommandContext context, Document document) {
                return Mono.just(SendMessage.builder().chatId(context.getChatId()).text("never").build());
            }
        };
        var handler = handler(DENY, List.of(csvHandler));
        var updates = Flux.just(Fixtures.wrap(Fixtures.documentUpdate(1, Fixtures.CHAT_ID, 100, "data.csv")));

        StepVerifier.create(handler.handleUpdates(updates))
            .verifyError(BotAuthException.class);
    }
}