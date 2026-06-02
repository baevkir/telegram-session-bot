package com.kb.sessionbot.errors.handler;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.exception.ExceptionUtils.getThrowableList;

/**
 * Routes a thrown error to the {@link ErrorHandler} registered for its exception type,
 * walking the cause chain from the root outward. Logs and swallows the error when no
 * handler matches.
 */
@Slf4j
public class ErrorHandlerFactory {
    private final List<ErrorHandler<?>> errorHandlers;
    private final Map<Class<Throwable>, ErrorHandler<Throwable>> errorHandlerMap = new HashMap<>();

    public ErrorHandlerFactory(List<ErrorHandler<?>> errorHandlers) {
        this.errorHandlers = errorHandlers;
    }

    public Mono<? extends PartialBotApiMethod<?>> handle(Throwable exception) {
        for (Throwable currentError : Lists.reverse(getThrowableList(exception))) {
            ErrorHandler<Throwable> errorHandler = errorHandlerMap.get(currentError.getClass());
            if (errorHandler != null) {
                return errorHandler.handle(currentError);
            }
        }
        log.error("Error during chat bot command", exception);
        return Mono.empty();
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        errorHandlers.forEach(errorHandler -> {
            Class<Throwable> type = ((Class<Throwable>) ((ParameterizedType) errorHandler.getClass().getGenericInterfaces()[0]).getActualTypeArguments()[0]);
            errorHandlerMap.put(type, (ErrorHandler<Throwable>) errorHandler);
        });
    }
}
