package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.assistant;

import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

import dev.langchain4j.service.spring.AiService;

import static dev.langchain4j.service.spring.AiServiceWiringMode.EXPLICIT;

/**
 * Wiring EXPLICIT: liga apenas os beans nomeados, evitando que outros ChatModel/ContentRetriever
 * do contexto sejam associados automaticamente.
 * {@link ChatMemoryAccess} permite limpar a memória do chat quando começa uma nova consultoria por foto.
 */
@AiService(
        wiringMode = EXPLICIT,
        chatModel = "bellaChatModel",
        chatMemoryProvider = "bellaChatMemoryProvider",
        retrievalAugmentor = "bellaRetrievalAugmentor"
)
public interface BellaBotAssistant extends ChatMemoryAccess {

    @SystemMessage(fromResource = "prompts/bella-system.md")
    /**
     * O texto vai como {@link TextContent} (e não String) para não ser interpretado como template:
     * mensagens do usuário com "{{...}}" quebrariam a renderização.
     */
    String reply(@MemoryId Long chatId, @UserMessage TextContent message);
}
