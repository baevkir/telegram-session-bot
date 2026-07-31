package com.kb.sessionbot;

import com.kb.sessionbot.model.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Update;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Default in-process {@link InboundUpdateBus}. Updates are buffered in a unicast Reactor sink,
 * grouped per chat with {@code groupBy}, and each chat's stream is completed after {@code idleTtl}
 * of inactivity via {@code timeout} (the safety net for abandoned conversations; completed commands
 * are released earlier by the handler). The single producer is the long-polling thread, so
 * {@code emit} uses {@code FAIL_FAST}.
 */
@Slf4j
public class SinkInboundUpdateBus implements InboundUpdateBus {

    private final Sinks.Many<Update> sink = Sinks.many().unicast().onBackpressureBuffer();
    private final Duration idleTtl;

    public SinkInboundUpdateBus(Duration idleTtl) {
        this.idleTtl = idleTtl;
    }

    @Override
    public void emit(Update update) {
        sink.emitNext(update, Sinks.EmitFailureHandler.FAIL_FAST);
    }

    @Override
    public Flux<ChatUpdateStream> updates() {
        return sink.asFlux()
            .<UpdateWrapper>handle((update, downstream) -> {
                if (!update.hasMessage() && !update.hasCallbackQuery()) {
                    log.warn("Skipping update {} without a chat id", update.getUpdateId());
                    return;
                }
                try {
                    downstream.next(UpdateWrapper.wrap(update));
                } catch (RuntimeException wrapFailure) {
                    // A single malformed update must never terminate the shared root stream (every
                    // chat's updates flow through it) - log and drop instead of propagating an error.
                    log.warn("Skipping update {} that failed to wrap", update.getUpdateId(), wrapFailure);
                }
            })
            .groupBy(UpdateWrapper::getChatId)
            .map(group -> new ChatUpdateStream(group.key(), group.timeout(idleTtl, Flux.empty())));
    }
}
