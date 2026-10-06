package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.assistant;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import dev.langchain4j.service.spring.AiService;

import static dev.langchain4j.service.spring.AiServiceWiringMode.EXPLICIT;

/**
 * Wiring EXPLICIT: liga apenas os beans nomeados, evitando que outros ChatModel/ContentRetriever
 * do contexto sejam associados automaticamente.
 * Sem memória: cada análise é independente, e guardar o histórico reenviaria as fotos anteriores ao modelo.
 */
@AiService(
        wiringMode = EXPLICIT,
        chatModel = "bellaPictureModel"
)
public interface PictureDescriptionAssistant {

    @SystemMessage(fromResource = "prompts/picture-description-system.md")
    String analysis(@UserMessage ImageContent image);
}
