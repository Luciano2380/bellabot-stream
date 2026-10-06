package com.lpopas.bellabotstream.infrastructure.adapter.out.kafka;

import com.lpopas.bellabotstream.avro.BellaUserMessage;
import com.lpopas.bellabotstream.domain.event.MessageProcessedEvent;
import com.lpopas.bellabotstream.domain.model.IncomingMessage;
import com.lpopas.bellabotstream.domain.model.ProcessingStatus;
import com.lpopas.bellabotstream.domain.valueobject.Photo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEventPublisherTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-10-05T12:00:30Z");

    @Test
    void shouldMapAllRequiredFieldsToAvro() {
        var message = new IncomingMessage(10L, 20L, "maria", "Qual base?", RECEIVED_AT, "+5531999990000", List.of());
        var event = new MessageProcessedEvent(UUID.randomUUID(), message, "<b>Base</b> TimeWise", PROCESSED_AT);

        BellaUserMessage avro = KafkaEventPublisher.toAvro(event);

        assertThat(avro.getEventId()).isEqualTo(event.eventId().toString());
        assertThat(avro.getUsername()).isEqualTo("maria");
        assertThat(avro.getPhone()).isEqualTo("+5531999990000");
        assertThat(avro.getReply()).isEqualTo("Base TimeWise");
        assertThat(avro.getStatus()).isEqualTo(ProcessingStatus.ANSWERED.name());
        assertThat(avro.getReceivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void shouldMapPhotoWithoutPhoneAndUsernameToAvro() {
        var message = new IncomingMessage(10L, null, null, null, RECEIVED_AT, null, List.of(new Photo("f", 10, 10)));
        var event = new MessageProcessedEvent(UUID.randomUUID(), message, "Resposta", PROCESSED_AT);

        BellaUserMessage avro = KafkaEventPublisher.toAvro(event);

        assertThat(avro.getPhone()).isEmpty();
        assertThat(avro.getUsername()).isNull();
    }

    @Test
    void shouldRemoveTelegramHtmlTags() {
        assertThat(KafkaEventPublisher.toPlainText("<b>Base</b> e <i>blush</i>, <s>sem estoque</s> <u>hoje</u>"))
                .isEqualTo("Base e blush, sem estoque hoje");
    }

    @Test
    void shouldKeepUrlOfHtmlAndMarkdownLinks() {
        assertThat(KafkaEventPublisher.toPlainText(
                "<a href=\"https://loja.marykay.com.br/base\">Base TimeWise</a> e [Batom](https://loja.marykay.com.br/batom)"))
                .isEqualTo("Base TimeWise (https://loja.marykay.com.br/base) e Batom (https://loja.marykay.com.br/batom)");
    }

    @Test
    void shouldNotRepeatUrlWhenLinkTextIsTheUrl() {
        assertThat(KafkaEventPublisher.toPlainText("<a href=\"https://a.com\">https://a.com</a>"))
                .isEqualTo("https://a.com");
    }

    @Test
    void shouldRemoveMarkdownMarkup() {
        var markdown = """
                ## Sua análise
                ---
                **Pele** __oleosa__, *poros* _visíveis_ e ~~acne~~
                > use protetor
                `FPS 30`
                """;

        assertThat(KafkaEventPublisher.toPlainText(markdown))
                .isEqualTo("Sua análise\nPele oleosa, poros visíveis e acne\nuse protetor\nFPS 30");
    }

    @Test
    void shouldKeepBulletsAndSnakeCase() {
        assertThat(KafkaEventPublisher.toPlainText("• Limpe a pele\n- photo_status ok"))
                .isEqualTo("• Limpe a pele\n- photo_status ok");
    }

    @Test
    void shouldTurnBreakTagsIntoNewLinesAndUnwrapCode() {
        assertThat(KafkaEventPublisher.toPlainText("Linha 1<br>Linha 2<br/>```json\n{\"a\": 1}\n```"))
                .isEqualTo("Linha 1\nLinha 2\n{\"a\": 1}");
    }

    @Test
    void shouldDecodeEntitiesAfterRemovingTags() {
        assertThat(KafkaEventPublisher.toPlainText("<b>5 &lt; 10</b> &amp; &quot;ok&quot; &#233; &#x1F484; &amp;lt;b&amp;gt;"))
                .isEqualTo("5 < 10 & \"ok\" é 💄 &lt;b&gt;");
    }

    @Test
    void shouldKeepEscapedTagsAsText() {
        assertThat(KafkaEventPublisher.toPlainText("digite &lt;b&gt; para negrito"))
                .isEqualTo("digite <b> para negrito");
    }

    @Test
    void shouldCollapseBlankLinesAndTrim() {
        assertThat(KafkaEventPublisher.toPlainText("  <blockquote>Oi</blockquote>\n\n\n\nTchau  \n"))
                .isEqualTo("Oi\n\nTchau");
    }

    @Test
    void shouldReturnNullOrBlankAsIs() {
        assertThat(KafkaEventPublisher.toPlainText(null)).isNull();
        assertThat(KafkaEventPublisher.toPlainText("  ")).isEqualTo("  ");
    }
}
