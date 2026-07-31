package com.kb.sessionbot.documents;

import com.kb.sessionbot.model.CommandContext;
import org.reactivestreams.Publisher;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Document;

/**
 * Handles a document sent outside any command flow. Implemented as Spring beans by the host bot;
 * the first handler whose {@link #supports} matches wins. When no handler matches (or none are
 * registered) the update falls through to the default help behavior.
 */
public interface DocumentHandler {

    boolean supports(Document document);

    Publisher<PartialBotApiMethod<?>> handle(CommandContext context, Document document);
}
