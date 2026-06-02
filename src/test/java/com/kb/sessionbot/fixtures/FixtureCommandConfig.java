package com.kb.sessionbot.fixtures;

import com.kb.sessionbot.commands.dispatcher.parameters.BooleanParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.DateParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRenderer;
import com.kb.sessionbot.commands.dispatcher.parameters.ParameterRendererFactory;
import com.kb.sessionbot.commands.dispatcher.parameters.TextParameterRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public ParameterRenderer textParameterRenderer() {
        return new TextParameterRenderer();
    }

    @Bean
    public ParameterRenderer dateParameterRenderer() {
        return new DateParameterRenderer();
    }

    @Bean
    public ParameterRenderer booleanParameterRenderer() {
        return new BooleanParameterRenderer();
    }

    @Bean
    public ParameterRenderer defaultParameterRenderer(
        ParameterRenderer textParameterRenderer,
        ParameterRenderer dateParameterRenderer,
        ParameterRenderer booleanParameterRenderer) {
        return new ParameterRendererFactory(textParameterRenderer, dateParameterRenderer, booleanParameterRenderer);
    }
}