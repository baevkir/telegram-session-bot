package com.kb.sessionbot.commands.dispatcher.parameters;

import com.kb.sessionbot.commands.CommandBuilder;
import org.reactivestreams.Publisher;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.ObjectUtils.isEmpty;
import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

public class TextParameterRenderer implements ParameterRenderer {
    @Override
    public Publisher<? extends PartialBotApiMethod<?>> render(ParameterRequest parameterRequest) {
        return Mono.fromSupplier(() -> {
            var messageBuilder = SendMessage.builder()
                .chatId(parameterRequest.getContext().getChatId())
                .text(parameterRequest.getText())
                .parseMode(ParseMode.HTML);

            List<InlineKeyboardRow> rowsInline = new ArrayList<>();
            if (!isEmpty(parameterRequest.getOptions())) {
                InlineKeyboardRow rowInline = parameterRequest.getOptions().stream()
                    .map(option ->
                        InlineKeyboardButton.builder()
                            .text(option.getValue())
                            .callbackData(option.getKey())
                            .build()
                    )
                    .collect(Collectors.toCollection(InlineKeyboardRow::new));

                rowsInline.add(rowInline);
            }
            if (!parameterRequest.isRequired()) {
                rowsInline.add(
                    new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("Пропусить")
                        .callbackData(CommandBuilder.create().scipAnswer(parameterRequest.getIndex()).build())
                        .build())
                );
            }
            if (isNotEmpty(rowsInline)) {
                messageBuilder.replyMarkup(InlineKeyboardMarkup.builder().keyboard(rowsInline).build());
            }
            return messageBuilder.build();
        });
    }
}