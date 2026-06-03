package com.kb.sessionbot.commands.dispatcher.parameters;

import com.kb.sessionbot.commands.CommandBuilder;
import com.kb.sessionbot.i18n.BotLabels;
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

public class BooleanParameterRenderer implements ParameterRenderer {
    private final BotLabels labels;

    public BooleanParameterRenderer(BotLabels labels) {
        this.labels = labels;
    }

    @Override
    public Publisher<? extends PartialBotApiMethod<?>> render(ParameterRequest parameterRequest) {
        return Mono.fromSupplier(() -> {
            List<InlineKeyboardRow> rowsInline = new ArrayList<>();
            InlineKeyboardRow rowInline = new InlineKeyboardRow(
                InlineKeyboardButton.builder().text(labels.yes(parameterRequest.getContext())).callbackData(Boolean.toString(true)).build(),
                InlineKeyboardButton.builder().text(labels.no(parameterRequest.getContext())).callbackData(Boolean.toString(false)).build()
            );

            rowsInline.add(rowInline);

            if (!parameterRequest.isRequired()) {
                rowsInline.add(
                    new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text(labels.skip(parameterRequest.getContext()))
                        .callbackData(CommandBuilder.create().scipAnswer(parameterRequest.getIndex()).build())
                        .build())
                );
            }
            return SendMessage.builder()
                .chatId(parameterRequest.getContext().getChatId())
                .text(parameterRequest.getText())
                .parseMode(ParseMode.HTML)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rowsInline).build())
                .build();
        });
    }
}