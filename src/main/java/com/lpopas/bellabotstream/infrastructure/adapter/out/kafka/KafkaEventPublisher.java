package com.lpopas.bellabotstream.infrastructure.adapter.out.kafka;

import com.lpopas.bellabotstream.application.port.out.PublishEventPort;
import com.lpopas.bellabotstream.avro.BellaUserMessage;
import com.lpopas.bellabotstream.domain.event.MessageProcessedEvent;
import com.lpopas.bellabotstream.domain.model.IncomingMessage;
import com.lpopas.bellabotstream.domain.model.ProcessingStatus;
import com.lpopas.bellabotstream.infrastructure.config.kafka.KafkaTopicProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publica o evento no tópico bella.user-message.processed.v1, usando o chatId como chave
 * para preservar a ordem das mensagens de uma mesma conversa.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventPublisher implements PublishEventPort {

    private static final Pattern CODE_FENCE = Pattern.compile("```[\\w+-]*[ \\t]*\\n?(.*?)```", Pattern.DOTALL);
    private static final Pattern HTML_LINK = Pattern.compile("(?is)<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>");
    private static final Pattern LINE_BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>|</p>|</blockquote>|</pre>");
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][\\w-]*(\\s[^<>]*)?/?>");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^[ \\t]*#{1,6}[ \\t]+(.+?)[ \\t#]*$");
    private static final Pattern MD_HORIZONTAL_RULE = Pattern.compile("(?m)^[ \\t]*([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$\\n?");
    private static final Pattern MD_BLOCKQUOTE = Pattern.compile("(?m)^[ \\t]*>[ \\t]?");
    private static final Pattern MD_BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern MD_STRIKETHROUGH = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern MD_ITALIC_ASTERISK = Pattern.compile("(?<![*\\w])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![*\\w])");
    // Exige fronteira de palavra para não quebrar nomes como snake_case.
    private static final Pattern MD_ITALIC_UNDERSCORE = Pattern.compile("(?<![_\\w])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![_\\w])");
    private static final Pattern MD_INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]{1,6}|\\d{1,7});");
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t]+$");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topics;

    @Override
    public void publish(MessageProcessedEvent event) {
        String key = event.message().chatId().toString();
        kafkaTemplate.send(topics.userMessage(), key, toAvro(event))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Falha ao publicar evento {} em {}", event.eventId(), topics.userMessage(), ex);
                    } else {
                        log.debug("Evento {} publicado em {}-{}@{}", event.eventId(),
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    static BellaUserMessage toAvro(MessageProcessedEvent event) {
        IncomingMessage message = event.message();
        return BellaUserMessage.newBuilder()
                .setEventId(event.eventId().toString())
                .setUsername(message.username())
                // O telefone só vem quando o usuário compartilha o contato; o schema exige string.
                .setPhone(message.phone() != null ? message.phone() : "")
                .setReply(toPlainText(event.reply()))
                // Só mensagens processadas com sucesso são publicadas (ver ReplyToMessageService).
                .setStatus(ProcessingStatus.ANSWERED.name())
                .setReceivedAt(message.receivedAt())
                .build();
    }

    /**
     * A resposta enviada ao Telegram vem em HTML (e às vezes com Markdown residual do modelo).
     * No evento ela vai como texto puro: sem tags, sem marcação Markdown e com as entidades decodificadas.
     * Links viram "texto (url)" para não perder o endereço.
     */
    static String toPlainText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        var result = CODE_FENCE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1).stripTrailing()));

        // HTML: links, quebras de linha e depois as demais tags.
        result = HTML_LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(linkText(m.group(2), m.group(1))));
        result = LINE_BREAK_TAG.matcher(result).replaceAll("\n");
        result = HTML_TAG.matcher(result).replaceAll("");

        // Markdown: blocos (linhas) e depois o inline.
        result = MD_HORIZONTAL_RULE.matcher(result).replaceAll("");
        result = MD_HEADING.matcher(result).replaceAll("$1");
        result = MD_BLOCKQUOTE.matcher(result).replaceAll("");
        result = MD_LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(linkText(m.group(1), m.group(2))));
        result = MD_BOLD.matcher(result).replaceAll("$2");
        result = MD_STRIKETHROUGH.matcher(result).replaceAll("$1");
        result = MD_ITALIC_ASTERISK.matcher(result).replaceAll("$1");
        result = MD_ITALIC_UNDERSCORE.matcher(result).replaceAll("$1");
        result = MD_INLINE_CODE.matcher(result).replaceAll("$1");

        // Entidades por último, para que um "&lt;b&gt;" escrito pelo usuário vire "<b>" literal, e não seja removido.
        result = decodeEntities(result);
        result = TRAILING_SPACES.matcher(result).replaceAll("");
        return EXTRA_BLANK_LINES.matcher(result).replaceAll("\n\n").strip();
    }

    private static String linkText(String label, String url) {
        var cleanLabel = HTML_TAG.matcher(label).replaceAll("").strip();
        return cleanLabel.isEmpty() || cleanLabel.equals(url) ? url : cleanLabel + " (" + url + ")";
    }

    private static String decodeEntities(String text) {
        var result = NUMERIC_ENTITY.matcher(text).replaceAll(m -> {
            var value = m.group(1);
            int codePoint = value.startsWith("x") ? Integer.parseInt(value.substring(1), 16) : Integer.parseInt(value);
            // Código inválido fica como está, em vez de derrubar a publicação.
            return Matcher.quoteReplacement(Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : m.group());
        });
        // &amp; por último: "&amp;lt;" deve virar "&lt;", e não "<".
        return result.replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

}
