package com.lpopas.bellabotstream.domain.event;

import com.lpopas.bellabotstream.domain.model.IncomingMessage;
import com.lpopas.bellabotstream.domain.model.ProcessingStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Evento emitido após o bot processar (e responder) uma mensagem do usuário.
 * Agnóstico de transporte: a conversão para Avro acontece no adapter Kafka.
 */
public record MessageProcessedEvent(
        UUID eventId,
        IncomingMessage message,
        String reply,
        Instant processedAt
) {

    public MessageProcessedEvent {

    }

    public static MessageProcessedEvent of(IncomingMessage message, String reply) {
        return new MessageProcessedEvent(UUID.randomUUID(), message, reply, Instant.now());
    }
}
