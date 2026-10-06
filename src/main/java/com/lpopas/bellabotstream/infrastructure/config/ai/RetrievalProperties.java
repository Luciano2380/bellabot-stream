package com.lpopas.bellabotstream.infrastructure.config.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros da busca no RAG usada pelo {@code ContentRetriever} do BellaBotAssistant.
 *
 * @param maxResults quantidade máxima de segmentos devolvidos por consulta
 * @param minScore   similaridade mínima (0 a 1) para um segmento entrar no prompt
 */
@ConfigurationProperties(prefix = "bellabot.ai.retrieval")
public record RetrievalProperties(Integer maxResults, Double minScore) {

    public RetrievalProperties {
        maxResults = maxResults == null ? 5 : maxResults;
        minScore = minScore == null ? 0.83 : minScore;
    }
}
