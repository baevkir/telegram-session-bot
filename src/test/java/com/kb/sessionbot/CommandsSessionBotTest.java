package com.kb.sessionbot;

import com.kb.sessionbot.auth.AuthInterceptor;
import com.kb.sessionbot.commands.CommandsFactory;
import com.kb.sessionbot.commands.HelpCommand;
import com.kb.sessionbot.commands.IBotCommand;
import com.kb.sessionbot.commands.dispatcher.DispatcherBotCommand;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class CommandsSessionBotTest {

    private static final AuthInterceptor ALLOW = ctx -> Mono.just(true);
    private static final AuthInterceptor DENY = ctx -> Mono.just(false);

    private AnnotationConfigApplicationContext springContext;
    private TelegramClient telegramClient;
    private CommandsFactory commandsFactory;
    private ErrorHandlerFactory errorHandlerFactory;
    private OutboundMessageBus outboundMessageBus;

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

        outboundMessageBus = new SinkOutboundMessageBus();
    }

    @AfterEach
    void tearDown() {
        springContext.close();
    }

    private CommandsSessionBot bot(AuthInterceptor auth) {
        var executor = new TelegramClientMessageExecutor(telegramClient, errorHandlerFactory);
        var updateHandler = new TelegramUpdateHandler(commandsFactory, auth, executor);
        var inboundUpdateBus = new SinkInboundUpdateBus(Duration.ofMinutes(30));
        return new CommandsSessionBot(
            commandsFactory, errorHandlerFactory, executor, outboundMessageBus, updateHandler,
            inboundUpdateBus, 256);
    }

    @DisplayName("consume() drives an update end-to-end to telegramClient.execute")
    @Test
    void consumeEndToEnd() throws Exception {
        var bot = bot(ALLOW);
        bot.init(); // wires the reactive pipeline and emits SetMyCommands
        bot.consume(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy"));

        // SetMyCommands at startup + the executed prompt from the in-progress command.
        verify(telegramClient, timeout(2000).atLeast(2)).execute(any(BotApiMethod.class));
    }

    @DisplayName("a chat whose handler errors does not terminate the updates subscription (failure isolation)")
    @Test
    void failureInOneChatDoesNotKillPipeline() throws Exception {
        var bot = bot(DENY); // auth denial makes the first chat's dispatch error
        bot.init();

        bot.consume(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy&book"));
        long otherChat = Fixtures.CHAT_ID + 1;
        bot.consume(Fixtures.messageUpdate(2, otherChat, 200, "anything"));

        // SetMyCommands + the auth error message + chat B's help response all execute.
        verify(telegramClient, timeout(5000).atLeast(3)).execute(any(BotApiMethod.class));
    }

    @DisplayName("out-of-band messages pushed via OutboundMessageBus execute through the coordinator")
    @Test
    void outboundMessagesExecuteThroughCoordinator() throws Exception {
        var bot = bot(ALLOW);
        bot.init();

        outboundMessageBus.send(SendMessage.builder()
            .chatId(String.valueOf(Fixtures.CHAT_ID)).text("out-of-band").build());

        // SetMyCommands + the out-of-band SendMessage.
        verify(telegramClient, timeout(5000).atLeast(2)).execute(any(BotApiMethod.class));
    }

    private static com.kb.sessionbot.i18n.BotLabels testLabels() {
        var ms = new org.springframework.context.support.ResourceBundleMessageSource();
        ms.setBasenames("sessionbot-labels");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new com.kb.sessionbot.i18n.BotLabels(ms, new com.kb.sessionbot.i18n.ConfiguredLocaleProvider(java.util.Locale.ENGLISH));
    }

    @DisplayName("@PreDestroy disposes the subscription composite")
    @Test
    void shutdownDisposesSubscriptions() throws Exception {
        var bot = bot(ALLOW);
        bot.init();
        bot.shutdown();

        // After disposal, a freshly consumed update is dropped by the (disposed) updates
        // subscription; only the synchronous startup SetMyCommands ever executed.
        bot.consume(Fixtures.messageUpdate(3, Fixtures.CHAT_ID, 300, "/order?buy"));
        verify(telegramClient, atMost(1)).execute(any(BotApiMethod.class));
    }
}
