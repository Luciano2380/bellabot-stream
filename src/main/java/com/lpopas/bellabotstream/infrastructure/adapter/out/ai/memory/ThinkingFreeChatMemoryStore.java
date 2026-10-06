package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.memory;

import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.MedgemmaThinking;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

import java.util.List;
import java.util.Objects;

/**
 * Guarda a conversa sem o raciocínio do medgemma ({@code <unused94>…<unused95>}).
 * <p>
 * Cada resposta bruta traz de 5 a 11 mil caracteres de raciocínio. Guardado na memória, ele era reenviado
 * a cada mensagem e, após algumas trocas no mesmo chat, estourava a janela de contexto (num-ctx 8192):
 * o Ollama truncava a conversa e o modelo devolvia restos sem sentido.
 */
public class ThinkingFreeChatMemoryStore implements ChatMemoryStore {

    private final ChatMemoryStore delegate;

    public ThinkingFreeChatMemoryStore() {
        this(new InMemoryChatMemoryStore());
    }

    ThinkingFreeChatMemoryStore(ChatMemoryStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return delegate.getMessages(memoryId);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        delegate.updateMessages(memoryId, messages.stream()
                .map(ThinkingFreeChatMemoryStore::withoutThinking)
                .filter(Objects::nonNull)
                .toList());
    }

    @Override
    public void deleteMessages(Object memoryId) {
        delegate.deleteMessages(memoryId);
    }

    /** Resposta que só tinha raciocínio (cortada pelo num-predict) é descartada: não há o que lembrar. */
    private static ChatMessage withoutThinking(ChatMessage message) {
        if (!(message instanceof AiMessage ai) || ai.text() == null || !ai.text().contains("<unused94>")) {
            return message;
        }
        String text = MedgemmaThinking.remove(ai.text());
        if (text.isEmpty() && !ai.hasToolExecutionRequests()) {
            return null;
        }
        return ai.withText(text);
    }
}
