package com.kb.sessionbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "sessionbot.telegram")
public class CommandsSessionBotProperties {
    private String token;
    private String botUsername;
    /** Idle period after which an inactive chat's update stream is released. */
    private Duration chatIdleTtl = Duration.ofMinutes(30);
    /** Maximum number of chats processed concurrently (per-chat fan-out concurrency). */
    private int maxConcurrentChats = 256;
}
