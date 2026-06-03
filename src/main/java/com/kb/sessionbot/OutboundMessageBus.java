package com.kb.sessionbot;

import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import reactor.core.publisher.Flux;

/**
 * Decoupled outbound message bus: producers {@link #send} out-of-band messages and the
 * coordinator drains {@link #messages()} to execute them. The default implementation buffers
 * in process via Reactor {@code Sinks}; a consuming app can swap in a queue-backed bus
 * (e.g. Kafka/SQS) by declaring its own bean.
 */
public interface OutboundMessageBus {

    void send(PartialBotApiMethod<?> message);

    Flux<PartialBotApiMethod<?>> messages();
}
