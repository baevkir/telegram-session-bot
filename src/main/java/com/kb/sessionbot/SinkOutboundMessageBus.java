package com.kb.sessionbot;

import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Default in-process {@link OutboundMessageBus} backed by a unicast Reactor
 * {@link Sinks.Many}. Concurrent {@link #send} calls are serialized via busy-looping so no
 * message is dropped under contention.
 */
public class SinkOutboundMessageBus implements OutboundMessageBus {

    private final Sinks.Many<PartialBotApiMethod<?>> messagesSink = Sinks.many().unicast().onBackpressureBuffer();

    @Override
    public void send(PartialBotApiMethod<?> message) {
        messagesSink.emitNext(message, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
    }

    @Override
    public Flux<PartialBotApiMethod<?>> messages() {
        return messagesSink.asFlux();
    }
}
