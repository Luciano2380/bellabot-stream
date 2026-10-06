package com.lpopas.bellabotstream.infrastructure.config.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramBotProperties(
        String token,
        String username,
        String apiUrl
) {
}
