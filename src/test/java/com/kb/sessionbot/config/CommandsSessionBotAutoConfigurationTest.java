package com.kb.sessionbot.config;

import com.kb.sessionbot.CommandsSessionBot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CommandsSessionBotAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommandsSessionBotConfiguration.class));

    @Test
    void backsOffWhenTelegramPropertiesAbsent() {
        runner.run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(CommandsSessionBot.class);
        });
    }
}
