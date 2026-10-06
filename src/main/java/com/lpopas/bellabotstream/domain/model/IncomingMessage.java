package com.lpopas.bellabotstream.domain.model;

import com.lpopas.bellabotstream.domain.valueobject.Photo;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Mensagem enviada por um usuário ao bot: texto (ou legenda) e, opcionalmente, as resoluções de uma foto.
 */
public record IncomingMessage(
        Long chatId,
        Long userId,
        String username,
        String text,
        Instant receivedAt,
        String phone,
        List<Photo> photos
) {

    public IncomingMessage {
        photos = photos == null ? List.of() : List.copyOf(photos);
    }

    /**
     * A mesma foto chega em várias resoluções; a de maior área tem a melhor qualidade.
     */
    public Optional<Photo> largestPhoto() {
        return photos.stream().max(Comparator.comparingLong(Photo::area));
    }
}
