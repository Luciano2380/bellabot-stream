package com.lpopas.bellabotstream.domain.valueobject;

import java.util.Objects;

/**
 * Uma das resoluções de uma foto enviada pelo usuário.
 *
 * @param fileId identificador do arquivo na plataforma de mensagens
 * @param width  largura em pixels
 * @param height altura em pixels
 */
public record Photo(String fileId, int width, int height) {

    public Photo {
        Objects.requireNonNull(fileId, "fileId");
    }

    public long area() {
        return (long) width * height;
    }
}
