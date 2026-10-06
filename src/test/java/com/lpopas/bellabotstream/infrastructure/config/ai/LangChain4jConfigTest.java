package com.lpopas.bellabotstream.infrastructure.config.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jConfigTest {

    @Test
    void shouldInjectRagContentsInDelimitedPortugueseBlock() {
        var augmentor = new LangChain4jConfig().bellaRetrievalAugmentor(
                query -> List.of(Content.from("Produto: Base Matte"), Content.from("Link: https://a.com")));
        var userMessage = UserMessage.from("Qual base? {{nao_e_variavel}}");

        var result = augmentor.augment(new AugmentationRequest(userMessage, Metadata.from(userMessage, 10L, List.of())));

        assertThat(((UserMessage) result.chatMessage()).singleText()).isEqualTo("""
                Qual base? {{nao_e_variavel}}

                [MATERIAL DE CONSULTA]
                Produto: Base Matte

                Link: https://a.com
                [FIM DO MATERIAL]""");
    }
}
