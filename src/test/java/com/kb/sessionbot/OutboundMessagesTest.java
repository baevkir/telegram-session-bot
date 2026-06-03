package com.kb.sessionbot;

import com.kb.sessionbot.errors.handler.BotAuthErrorHandler;
import com.kb.sessionbot.errors.handler.BotCommandErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandler;
import com.kb.sessionbot.errors.handler.ErrorHandlerFactory;
import com.kb.sessionbot.fixtures.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class OutboundMessagesTest {

    @DisplayName("sendMessage enqueues a message that messages() emits")
    @Test
    void sendMessageEnqueuesAndMessagesEmits() {
        var outbound = new OutboundMessages();
        var msg = SendMessage.builder().chatId(String.valueOf(Fixtures.CHAT_ID)).text("hi").build();

        StepVerifier.create(outbound.messages())
            .then(() -> outbound.sendMessage(msg))
            .expectNext(msg)
            .thenCancel()
            .verify();
    }

    @DisplayName("concurrent sendMessage from multiple threads loses no message (busyLooping guarantee)")
    @Test
    void concurrentSendMessageLosesNothing() throws Exception {
        var outbound = new OutboundMessages();
        var telegramClient = Mockito.mock(TelegramClient.class);
        Mockito.when(telegramClient.execute(any(BotApiMethod.class)))
            .thenReturn(Fixtures.message(Fixtures.CHAT_ID, 999, "sent"));
        var errorHandlerFactory = new ErrorHandlerFactory(
            List.<ErrorHandler<?>>of(new BotCommandErrorHandler(), new BotAuthErrorHandler()));
        errorHandlerFactory.init();
        var executor = new TelegramClientMessageExecutor(telegramClient, errorHandlerFactory);

        // Drain the outbound queue exactly as the coordinator does (publishOn + execute).
        var subscription = outbound.messages()
            .publishOn(Schedulers.boundedElastic())
            .doOnNext(executor::execute)
            .subscribe();

        int threads = 8;
        int perThread = 25;
        int total = threads * perThread;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var sent = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            outbound.sendMessage(SendMessage.builder()
                                .chatId(String.valueOf(Fixtures.CHAT_ID))
                                .text("m" + sent.getAndIncrement())
                                .build());
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Every sendMessage executed; busyLooping must not drop any.
        verify(telegramClient, timeout(5000).times(total)).execute(any(BotApiMethod.class));
        subscription.dispose();
    }
}