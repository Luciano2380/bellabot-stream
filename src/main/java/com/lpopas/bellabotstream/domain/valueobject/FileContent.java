package com.lpopas.bellabotstream.domain.valueobject;

import java.util.Objects;

/**
 * Conteúdo de um arquivo enviado pelo usuário, com o tipo informado pela plataforma de mensagens.
 *
 * @param content  bytes do arquivo
 * @param mimeType tipo do conteúdo (ex.: image/jpeg)
 */
public record FileContent(byte[] content, String mimeType) {

    public FileContent {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(mimeType, "mimeType");
    }

    public boolean isEmpty() {
        return content.length == 0;
    }

    public int size() {
        return content.length;
    }
}
