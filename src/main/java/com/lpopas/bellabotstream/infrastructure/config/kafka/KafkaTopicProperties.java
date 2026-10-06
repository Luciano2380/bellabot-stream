package com.lpopas.bellabotstream.infrastructure.config.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bellabot.kafka.topics")
public record KafkaTopicProperties(
        String userMessage,
        int partitions,
        short replicationFactor
) {
}
