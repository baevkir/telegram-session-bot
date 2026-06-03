package com.kb.sessionbot;

import com.kb.sessionbot.model.UpdateWrapper;
import reactor.core.publisher.Flux;

/** A single chat's ordered stream of updates, tagged with its chat id. */
public record ChatUpdateStream(String chatId, Flux<UpdateWrapper> updates) {}
