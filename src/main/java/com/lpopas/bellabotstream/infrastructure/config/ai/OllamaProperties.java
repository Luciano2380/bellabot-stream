package com.lpopas.bellabotstream.infrastructure.config.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "bellabot.ai.ollama")
public record OllamaProperties(
        String baseUrl,
        Duration timeout,
        Integer maxRetries,
        boolean logRequests,
        boolean logResponses,
        Chat chat,
        Picture picture,
        Embedding embedding
) {

    public OllamaProperties {
        picture = picture == null ? new Picture(null) : picture;
    }

    public record Chat(String modelName, Double temperature, Integer numCtx, Integer numPredict, Double repeatPenalty,
                       Integer maxRetries) {
    }

    /**
     * Análise de foto: usa o mesmo modelo do chat, mas com teto de tokens próprio,
     * porque a "análise completa" pedida no prompt não cabe no num-predict do chat.
     */
    public record Picture(Integer numPredict) {

        public Picture {
            numPredict = numPredict == null ? 2048 : numPredict;
        }
    }

    public record Embedding(String modelName) {
    }
}
