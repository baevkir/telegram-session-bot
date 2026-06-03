package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.commands.dispatcher.parameters.BooleanParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.DateParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRendererFactory;
import com.kb.sessionbot.commands.dispatcher.parameters.TextParameterRenderer;
import com.kb.sessionbot.i18n.BotLabels;
import com.kb.sessionbot.i18n.ConfiguredLocaleProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

@Configuration
public class FixtureCommandConfig {

    @Bean
    public OrderCommand orderCommand() {
        return new OrderCommand();
    }

    @Bean
    public EchoCommand echoCommand() {
        return new EchoCommand();
    }

    @Bean
    public BotLabels botLabels() {
        var ms = new ResourceBundleMessageSource();
        ms.setBasenames("sessionbot-labels");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new BotLabels(ms, new ConfiguredLocaleProvider(Locale.ENGLISH));
    }

    @Bean
    public ParameterRenderer textParameterRenderer(BotLabels botLabels) {
        return new TextParameterRenderer(botLabels);
    }

    @Bean
    public ParameterRenderer dateParameterRenderer(BotLabels botLabels) {
        return new DateParameterRenderer(botLabels);
    }

    @Bean
    public ParameterRenderer booleanParameterRenderer(BotLabels botLabels) {
        return new BooleanParameterRenderer(botLabels);
    }

    @Bean
    public ParameterRenderer defaultParameterRenderer(
        ParameterRenderer textParameterRenderer,
        ParameterRenderer dateParameterRenderer,
        ParameterRenderer booleanParameterRenderer) {
        return new ParameterRendererFactory(textParameterRenderer, dateParameterRenderer, booleanParameterRenderer);
    }
}