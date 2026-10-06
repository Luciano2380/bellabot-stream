package com.lpopas.bellabotstream.infrastructure.config.telegram;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(TelegramBotProperties.class)
public class TelegramClientConfig {

    @Bean
    public TelegramClient telegramClient(TelegramBotProperties props) {
        return new OkHttpTelegramClient(props.token(), toTelegramUrl(props.apiUrl()));
    }

    private static TelegramUrl toTelegramUrl(String apiUrl) {
        URI uri = URI.create(apiUrl);
        int port = uri.getPort() != -1 ? uri.getPort() : ("http".equals(uri.getScheme()) ? 80 : 443);
        return new TelegramUrl(uri.getScheme(), uri.getHost(), port, false);
    }
}
