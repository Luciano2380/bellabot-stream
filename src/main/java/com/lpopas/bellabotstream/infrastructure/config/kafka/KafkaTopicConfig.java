package com.lpopas.bellabotstream.infrastructure.config.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Tópicos declarados como NewTopic são criados na inicialização pelo KafkaAdmin do Spring Boot.
 * O KafkaTemplate do producer é autoconfigurado a partir de spring.kafka.producer.*.
 */
@Configuration
@EnableConfigurationProperties(KafkaTopicProperties.class)
public class KafkaTopicConfig {

    @Bean
    public NewTopic bellaUserMessageTopic(KafkaTopicProperties props) {
        return TopicBuilder.name(props.userMessage())
                .partitions(props.partitions())
                .replicas(props.replicationFactor())
                .build();
    }
}
