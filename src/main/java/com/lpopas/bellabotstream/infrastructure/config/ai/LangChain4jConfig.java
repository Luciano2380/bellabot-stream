package com.lpopas.bellabotstream.infrastructure.config.ai;

import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.memory.ThinkingFreeChatMemoryStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OllamaProperties.class, ChromaProperties.class, RagProperties.class,
        RetrievalProperties.class})
public class LangChain4jConfig {

    @Bean
    public ChatModel bellaChatModel(OllamaProperties props) {
        return OllamaChatModel.builder()
                .baseUrl(props.baseUrl())
                .modelName(props.chat().modelName())
                .temperature(props.chat().temperature())
                .numCtx(props.chat().numCtx())
                .numPredict(props.chat().numPredict())
                .repeatPenalty(props.chat().repeatPenalty())
                .timeout(props.timeout())
                .maxRetries(props.chat().maxRetries())
                .logRequests(props.logRequests())
                .logResponses(props.logResponses())
                .build();
    }

    /**
     * Modelo da análise de foto. O modo JSON do Ollama restringe a saída a um objeto JSON:
     * sem blocos ``` nem o raciocínio do medgemma, que consumiam o teto de tokens e cortavam o JSON.
     */
    @Bean
    public ChatModel bellaPictureModel(OllamaProperties props) {
        return OllamaChatModel.builder()
                .baseUrl(props.baseUrl())
                .modelName(props.chat().modelName())
                .temperature(props.chat().temperature())
                .numCtx(props.chat().numCtx())
                .numPredict(props.picture().numPredict())
                .repeatPenalty(props.chat().repeatPenalty())
                .responseFormat(ResponseFormat.JSON)
                .timeout(props.timeout())
                .maxRetries(props.chat().maxRetries())
                .logRequests(props.logRequests())
                .logResponses(props.logResponses())
                .build();
    }

    @Bean
    public EmbeddingModel bellaEmbeddingModel(OllamaProperties props) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(props.baseUrl())
                .modelName(props.embedding().modelName())
                .timeout(props.timeout())
                .maxRetries(props.maxRetries())
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(ChromaProperties chromaProps) {
        return ChromaEmbeddingStore.builder()
                .baseUrl(chromaProps.baseUrl())
                .apiVersion(chromaProps.apiVersion())
                .tenantName(chromaProps.tenantName())
                .databaseName(chromaProps.databaseName())
                .collectionName(chromaProps.collectionName())
                .timeout(chromaProps.timeout())
                .logRequests(chromaProps.logRequests())
                .logResponses(chromaProps.logResponses())
                .build();
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel,
                                             RetrievalProperties retrievalProps) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(retrievalProps.maxResults())
                .minScore(retrievalProps.minScore())
                .build();
    }

    /**
     * Injeta os trechos do RAG num bloco delimitado e em português, no lugar do
     * "Answer using the following information:" padrão. O prompt da Bella (bella-system.md) referencia
     * esses delimitadores para tratar os trechos como material de consulta, sem copiá-los.
     */
    @Bean
    public RetrievalAugmentor bellaRetrievalAugmentor(ContentRetriever contentRetriever) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .contentInjector(DefaultContentInjector.builder()
                        .promptTemplate(PromptTemplate.from(
                                "{{userMessage}}\n\n[MATERIAL DE CONSULTA]\n{{contents}}\n[FIM DO MATERIAL]"))
                        .build())
                .build();
    }

    /**
     * Uma janela de memória por chat do Telegram (memoryId = chatId), guardada sem o raciocínio do medgemma.
     */
    @Bean
    public ChatMemoryProvider bellaChatMemoryProvider(
            @Value("${bellabot.ai.chat-memory.max-messages}") int maxMessages) {
        var store = new ThinkingFreeChatMemoryStore();
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build();
    }
}
