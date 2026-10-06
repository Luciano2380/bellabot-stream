package com.lpopas.bellabotstream.infrastructure.adapter.out.ai;

import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.assistant.BellaBotAssistant;
import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.assistant.PictureDescriptionAssistant;
import dev.langchain4j.data.message.TextContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChain4jReplyAdapterTest {

    @Test
    void shouldRemoveThinkingBlockAndExtractJson() {
        String raw = "<unused94>thought\nThe image is black.<unused95>```json\n"
                + "{\"photo_status\": \"NO_FACE\", \"message\": null}\n```";

        String json = LangChain4jReplyAdapter.extractJsonObject(MedgemmaThinking.remove(raw));

        assertThat(json).isEqualTo("{\"photo_status\": \"NO_FACE\", \"message\": null}");
    }

    @Test
    void shouldDiscardUnclosedThinkingBlock() {
        assertThat(MedgemmaThinking.remove("Olá!<unused94>thought sem fim")).isEqualTo("Olá!");
    }

    @Test
    void shouldKeepTextWithoutThinkingBlock() {
        assertThat(MedgemmaThinking.remove("  Olá, tudo bem?  ")).isEqualTo("Olá, tudo bem?");
    }

    @Test
    void shouldReportMissingAnalysisLabels() {
        var analysis = """
                Tom de pele: Clara (intensidade suave)
                Subtom: Quente — reflexos dourados
                Tipo de pele: Mista (estimativa) — brilho na testa
                Estação provável: Primavera Quente
                """;

        assertThat(LangChain4jReplyAdapter.missingLabels(analysis))
                .containsExactly("Contraste:", "Cores observadas:", "Limitações:");
    }

    @Test
    void shouldFrameConsultationAsCustomerRequest() {
        var guide = List.of("- Base e corretivo: fundo rosado", "- Intensidade da maquiagem: intensidade média");

        assertThat(LangChain4jReplyAdapter.consultationRequest("  Subtom: Frio — rosado\n", guide))
                .isEqualTo("""
                        Esta é a análise facial da foto que acabei de enviar:
                        Subtom: Frio — rosado

                        Orientações da consultoria para o meu perfil:
                        - Base e corretivo: fundo rosado
                        - Intensidade da maquiagem: intensidade média

                        Monte a minha consultoria.""");
    }

    @Test
    void shouldOmitGuideBlockWhenGuideIsEmpty() {
        assertThat(LangChain4jReplyAdapter.consultationRequest("Tom de pele: Clara", List.of()))
                .isEqualTo("Esta é a análise facial da foto que acabei de enviar:\nTom de pele: Clara\n\nMonte a minha consultoria.");
    }

    @Test
    void shouldMarkFreeTextAsClientMessage() {
        var assistant = mock(BellaBotAssistant.class);
        when(assistant.reply(any(), any())).thenReturn("<unused94>thought<unused95> De nada! 😊");
        var adapter = new LangChain4jReplyAdapter(assistant, mock(PictureDescriptionAssistant.class));

        assertThat(adapter.generate(10L, " Obrigada! ")).isEqualTo("De nada! 😊");

        var sent = ArgumentCaptor.forClass(TextContent.class);
        verify(assistant).reply(eq(10L), sent.capture());
        assertThat(sent.getValue().text()).isEqualTo("Mensagem do cliente: \"Obrigada!\"");
    }

    @Test
    void shouldNotMarkConsultationAsClientMessage() {
        var assistant = mock(BellaBotAssistant.class);
        when(assistant.reply(any(), any())).thenReturn("Consultoria");
        var adapter = new LangChain4jReplyAdapter(assistant, mock(PictureDescriptionAssistant.class));

        adapter.generateInvalidPhotoReply(10L);

        var sent = ArgumentCaptor.forClass(TextContent.class);
        verify(assistant).reply(eq(10L), sent.capture());
        assertThat(sent.getValue().text()).isEqualTo(LangChain4jReplyAdapter.INVALID_PHOTO_REQUEST);
    }

    @Test
    void shouldEvictChatMemoryBeforeNewConsultation() {
        var assistant = mock(BellaBotAssistant.class);
        when(assistant.reply(any(), any())).thenReturn("Consultoria");
        var adapter = new LangChain4jReplyAdapter(assistant, mock(PictureDescriptionAssistant.class));

        adapter.generateConsultation(10L, "Subtom: Quente", List.of());

        var order = inOrder(assistant);
        order.verify(assistant).evictChatMemory(10L);
        order.verify(assistant).reply(eq(10L), any());
    }

    @Test
    void shouldKeepChatMemoryForFreeText() {
        var assistant = mock(BellaBotAssistant.class);
        when(assistant.reply(any(), any())).thenReturn("Resposta");
        var adapter = new LangChain4jReplyAdapter(assistant, mock(PictureDescriptionAssistant.class));

        adapter.generate(10L, "E para olheiras?");

        verify(assistant, never()).evictChatMemory(any());
    }
}
