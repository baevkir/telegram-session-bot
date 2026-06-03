package com.kb.sessionbot;

import com.kb.sessionbot.fixtures.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SinkInboundUpdateBusTest {

    @DisplayName("emit announces one ChatUpdateStream tagged with the chat id")
    @Test
    void emitAnnouncesChatStream() {
        var bus = new SinkInboundUpdateBus(Duration.ofMinutes(30));

        StepVerifier.create(bus.updates())
            .then(() -> bus.emit(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy")))
            .assertNext(stream -> assertThat(stream.chatId()).isEqualTo(String.valueOf(Fixtures.CHAT_ID)))
            .thenCancel()
            .verify();
    }

    @DisplayName("distinct chats produce distinct streams, in arrival order")
    @Test
    void distinctChatsProduceDistinctStreams() {
        var bus = new SinkInboundUpdateBus(Duration.ofMinutes(30));

        StepVerifier.create(bus.updates().map(ChatUpdateStream::chatId))
            .then(() -> bus.emit(Fixtures.messageUpdate(1, 100L, 1, "/order?buy")))
            .then(() -> bus.emit(Fixtures.messageUpdate(2, 200L, 2, "/order?buy")))
            .expectNext("100", "200")
            .thenCancel()
            .verify();
    }

    @DisplayName("a chat's stream completes after the idle TTL with no updates")
    @Test
    void idleStreamCompletesAfterTtl() throws Exception {
        var bus = new SinkInboundUpdateBus(Duration.ofMillis(200));
        var completed = new CountDownLatch(1);

        // updates() is an unbounded outer stream, so assert that the INNER (per-chat) stream
        // completes after the idle TTL, not the outer.
        var subscription = bus.updates()
            .flatMap(stream -> stream.updates().doOnComplete(completed::countDown))
            .subscribe();

        bus.emit(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy"));

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
    }

    @DisplayName("a new update after idle completion recreates a fresh stream for the same chat")
    @Test
    void streamRecreatedAfterIdleCompletion() throws Exception {
        var bus = new SinkInboundUpdateBus(Duration.ofMillis(200));
        var chatIds = new CopyOnWriteArrayList<String>();
        var streamsAnnounced = new CountDownLatch(2);
        var firstCompleted = new CountDownLatch(1);

        var subscription = bus.updates()
            .flatMap(stream -> {
                chatIds.add(stream.chatId());
                streamsAnnounced.countDown();
                return stream.updates().doOnComplete(firstCompleted::countDown);
            })
            .subscribe();

        bus.emit(Fixtures.messageUpdate(1, Fixtures.CHAT_ID, 100, "/order?buy"));
        assertThat(firstCompleted.await(5, TimeUnit.SECONDS)).isTrue();   // first stream idle-completed

        bus.emit(Fixtures.messageUpdate(2, Fixtures.CHAT_ID, 101, "/order?buy"));
        assertThat(streamsAnnounced.await(5, TimeUnit.SECONDS)).isTrue(); // a fresh stream was announced

        assertThat(chatIds)
            .containsExactly(String.valueOf(Fixtures.CHAT_ID), String.valueOf(Fixtures.CHAT_ID));
        subscription.dispose();
    }
}
