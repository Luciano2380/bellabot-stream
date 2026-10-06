package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkingFreeChatMemoryStoreTest {

    private final ThinkingFreeChatMemoryStore store = new ThinkingFreeChatMemoryStore();

    @Test
    void shouldStoreAiMessagesWithoutThinking() {
        store.updateMessages(10L, List.of(
                SystemMessage.from("Você é a Bella."),
                UserMessage.from("Bom dia"),
                AiMessage.from("<unused94>thought\nraciocínio longo em inglês<unused95> Bom dia! 😊")));

        assertThat(store.getMessages(10L)).containsExactly(
                SystemMessage.from("Você é a Bella."),
                UserMessage.from("Bom dia"),
                AiMessage.from("Bom dia! 😊"));
    }

    @Test
    void shouldDropAiMessageThatWasOnlyTruncatedThinking() {
        store.updateMessages(10L, List.of(
                UserMessage.from("Obrigada!"),
                AiMessage.from("<unused94>thought\nraciocínio cortado pelo num-predict")));

        assertThat(store.getMessages(10L)).containsExactly(UserMessage.from("Obrigada!"));
    }

    @Test
    void shouldKeepAiMessagesWithoutThinkingUnchanged() {
        var answer = AiMessage.from("Use base matte.");
        store.updateMessages(10L, List.of(UserMessage.from("Qual base?"), answer));

        assertThat(store.getMessages(10L)).containsExactly(UserMessage.from("Qual base?"), answer);
    }

    @Test
    void shouldKeepConversationsSeparatedByMemoryIdAndDelete() {
        store.updateMessages(10L, List.of(UserMessage.from("chat 10")));
        store.updateMessages(20L, List.of(UserMessage.from("chat 20")));

        store.deleteMessages(10L);

        assertThat(store.getMessages(10L)).isEmpty();
        assertThat(store.getMessages(20L)).containsExactly(UserMessage.from("chat 20"));
    }
}
