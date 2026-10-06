package com.lpopas.bellabotstream.application.port.out;

import com.lpopas.bellabotstream.domain.model.PictureMessage;
import com.lpopas.bellabotstream.domain.valueobject.FileContent;

import java.util.List;

public interface GenerateAiReplyPort {

    String generate(Long chatId, String question);

    /**
     * Analisa uma foto do usuário.
     *
     * @param image conteúdo e tipo da imagem
     */
    PictureMessage analyzePhoto(Long chatId, FileContent image);

    /**
     * Gera a consultoria a partir da análise facial e das orientações já calculadas.
     * Como esse pedido é apresentado à LLM é decisão do adapter.
     *
     * @param analysis texto da análise facial (linhas rotuladas)
     * @param guide    orientações do {@code ConsultationGuide} (base, cores, intensidade, cuidados)
     */
    String generateConsultation(Long chatId, String analysis, List<String> guide);

    /** Gera a resposta pedindo uma nova foto, quando a enviada não pôde ser analisada. */
    String generateInvalidPhotoReply(Long chatId);
}
