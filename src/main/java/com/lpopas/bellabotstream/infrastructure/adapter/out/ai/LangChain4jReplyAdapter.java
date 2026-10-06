package com.lpopas.bellabotstream.infrastructure.adapter.out.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lpopas.bellabotstream.application.port.out.GenerateAiReplyPort;
import com.lpopas.bellabotstream.domain.model.PictureMessage;
import com.lpopas.bellabotstream.domain.model.enuns.PhotoFaceStatus;
import com.lpopas.bellabotstream.domain.valueobject.FileContent;
import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.assistant.BellaBotAssistant;
import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.assistant.PictureDescriptionAssistant;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jReplyAdapter implements GenerateAiReplyPort {

    // A aplicação não é web: não há bean ObjectMapper no contexto.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Rótulos que o picture-description-system.md exige em message; o bella-system.md depende deles. */
    static final List<String> ANALYSIS_LABELS = List.of(
            "Tom de pele:", "Subtom:", "Tipo de pele:", "Contraste:", "Estação provável:", "Cores observadas:", "Limitações:");

    /*
     * Pedidos à Bella no fluxo de foto. São escritos como falas do próprio cliente: o medgemma junta o prompt
     * de sistema e a mensagem num só turno, e rótulos técnicos (ou a análise crua) faziam o modelo resumir o
     * prompt em vez de responder. Os textos são citados no bella-system.md: altere os dois juntos.
     */
    static final String INVALID_PHOTO_REQUEST = "A foto que enviei não pôde ser analisada.";
    private static final String CONSULTATION_REQUEST =
            "Esta é a análise facial da foto que acabei de enviar:\n%s\n\n%sMonte a minha consultoria.";
    private static final String GUIDE_HEADER = "Orientações da consultoria para o meu perfil:\n";


    private final BellaBotAssistant assistant;
    private final PictureDescriptionAssistant pictureDescriptionAssistant;

    /**
     * O texto livre do cliente vai marcado como fala dele. Na primeira mensagem da conversa o prompt de sistema
     * é colado antes dela (o medgemma não tem papel de sistema), e uma mensagem curta como "Obrigada!" ou "👍"
     * era lida como reação às instruções: o modelo respondia "Entendido, estou pronta para atuar como Bella".
     */
    static final String CLIENT_MESSAGE = "Mensagem do cliente: \"%s\"";

    @Override
    public String generate(Long chatId, String question) {
        return ask(chatId, CLIENT_MESSAGE.formatted(question.strip()));
    }

    private String ask(Long chatId, String message) {
        String raw = assistant.reply(chatId, TextContent.from(message));
        String reply = MedgemmaThinking.remove(raw);
        if (reply.isEmpty()) {
            log.warn("Modelo devolveu resposta vazia após remover o raciocínio (chatId={}, tamanhoBruto={})",
                    chatId, raw != null ? raw.length() : 0);
            return reply;
        }
        return MarkdownToTelegramHtml.convert(reply);
    }

    @Override
    public PictureMessage analyzePhoto(Long chatId, FileContent image) {
        ImageContent imageContent = ImageContent.from(
                Base64.getEncoder().encodeToString(image.content()), image.mimeType());
        String json = extractJsonObject(MedgemmaThinking.remove(pictureDescriptionAssistant.analysis(imageContent)));
        try {
            PictureAnalysisResponse response = MAPPER.readValue(json, PictureAnalysisResponse.class);
            if (response.message() != null) {
                log.debug("Análise da foto (chatId={}):\n{}", chatId, response.message());
                List<String> missing = missingLabels(response.message());
                if (!missing.isEmpty()) {
                    log.warn("Análise da foto fora do formato esperado pela Bella (chatId={}): faltam {}", chatId, missing);
                }
            }
            return new PictureMessage(response.photoStatus(), response.message());
        } catch (JsonProcessingException e) {
            // JSON sem "}" final costuma indicar resposta cortada pelo num-predict (bellabot.ai.ollama.picture.num-predict).
            log.warn("JSON inválido devolvido pela análise de foto (chatId={}, tamanho={}, terminaComChave={})",
                    chatId, json.length(), json.stripTrailing().endsWith("}"));
            log.debug("JSON inválido da análise de foto (chatId={}): {}", chatId, json);
            throw new IllegalStateException("Resposta da análise de foto não é um JSON válido (chatId=" + chatId + ")", e);
        }
    }

    /**
     * Cada foto é uma consultoria nova: a memória do chat é limpa antes. Com o histórico, o modelo de 4B
     * copiava a última resposta da conversa (57 tokens, sem raciocínio) em vez de montar a consultoria.
     * A consultoria gerada entra na memória, então as perguntas seguintes ainda a enxergam.
     */
    @Override
    public String generateConsultation(Long chatId, String analysis, List<String> guide) {
        if (assistant.evictChatMemory(chatId)) {
            log.debug("Memória do chat limpa para a nova consultoria (chatId={})", chatId);
        }
        return ask(chatId, consultationRequest(analysis, guide));
    }

    @Override
    public String generateInvalidPhotoReply(Long chatId) {
        return ask(chatId, INVALID_PHOTO_REQUEST);
    }

    /** A análise seguida das orientações já calculadas; a Bella só redige a resposta. */
    static String consultationRequest(String analysis, List<String> guide) {
        var guideBlock = guide.isEmpty() ? "" : GUIDE_HEADER + String.join("\n", guide) + "\n\n";
        return CONSULTATION_REQUEST.formatted(analysis.strip(), guideBlock);
    }


    static List<String> missingLabels(String analysis) {
        return ANALYSIS_LABELS.stream().filter(label -> !analysis.contains(label)).toList();
    }

    /** O prompt pede JSON puro, mas o modelo pode envolvê-lo em ```json ... ``` ou em texto. */
    static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    /** Formato do JSON definido em prompts/picture-description-system.md. */
    private record PictureAnalysisResponse(
            @JsonProperty("photo_status") PhotoFaceStatus photoStatus,
            @JsonProperty("message") String message
    ) {}
}
