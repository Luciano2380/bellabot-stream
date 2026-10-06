package com.lpopas.bellabotstream.application.port.out;

import com.lpopas.bellabotstream.domain.valueobject.FileContent;

public interface SendMessagePort {

    void send(Long chatId, String text);

    /**
     * Baixa um arquivo enviado ao bot.
     *
     * @param fileId identificador do arquivo na plataforma de mensagens
     * @return bytes e tipo do arquivo, definido pelo adapter conforme a plataforma
     */
    FileContent downloadFile(String fileId);

    /**
     * Mostra "digitando..." ao usuário até o indicador ser fechado.
     * Use com try-with-resources em volta do processamento lento (chamadas à LLM).
     */
    TypingIndicator startTyping(Long chatId);

    /** Indicador ativo de "digitando..."; {@link #close()} o interrompe e não lança exceção. */
    interface TypingIndicator extends AutoCloseable {

        @Override
        void close();
    }
}
