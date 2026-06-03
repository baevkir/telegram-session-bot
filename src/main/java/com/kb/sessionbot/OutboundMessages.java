package com.kb.sessionbot;

import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Pure outbound queue for out-of-band messages. Consuming apps inject this bean (not the bot)
 * to push messages; the coordinator drains {@link #messages()} and executes each. Does not
 * itself execute.
 */
public class OutboundMessages {

    private final Sinks.Many<PartialBotApiMethod<?>> messagesSink = Sinks.many().unicast().onBackpressureBuffer();

    public void sendMessage(PartialBotApiMethod<?> message) {
        messagesSink.emitNext(message, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
    }

    public Flux<PartialBotApiMethod<?>> messages() {
        return messagesSink.asFlux();
    }
}