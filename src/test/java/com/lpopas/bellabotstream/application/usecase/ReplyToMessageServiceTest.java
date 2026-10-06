package com.lpopas.bellabotstream.application.usecase;

import com.lpopas.bellabotstream.application.port.out.SendMessagePort;
import com.lpopas.bellabotstream.application.port.out.GenerateAiReplyPort;
import com.lpopas.bellabotstream.application.port.out.PublishEventPort;
import com.lpopas.bellabotstream.domain.constants.BellaConstants;
import com.lpopas.bellabotstream.domain.event.MessageProcessedEvent;
import com.lpopas.bellabotstream.domain.model.IncomingMessage;
import com.lpopas.bellabotstream.domain.model.PictureMessage;
import com.lpopas.bellabotstream.domain.model.enuns.PhotoFaceStatus;
import com.lpopas.bellabotstream.domain.service.ConsultationGuide;
import com.lpopas.bellabotstream.domain.valueobject.FileContent;
import com.lpopas.bellabotstream.domain.valueobject.Photo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplyToMessageServiceTest {

    private static final IncomingMessage MESSAGE =
            new IncomingMessage(10L, 20L, "maria", "Quais horários tem amanhã?", Instant.now(), null, List.of());

    private static final IncomingMessage PHOTO_MESSAGE = new IncomingMessage(10L, 20L, "maria", null, Instant.now(), null,
            List.of(new Photo("small", 90, 90), new Photo("large", 1280, 1280), new Photo("medium", 320, 320)));
    private static final FileContent IMAGE = new FileContent(new byte[]{1, 2, 3}, "image/jpeg");
    private static final String ANALYSIS = "Pele de subtom quente.";
    private static final List<String> GUIDE = ConsultationGuide.forAnalysis(ANALYSIS);

    @Mock
    private GenerateAiReplyPort generateAiReplyPort;
    @Mock
    private SendMessagePort sendMessagePort;
    @Mock
    private PublishEventPort publishEventPort;

    @InjectMocks
    private ReplyToMessageService service;

    @Test
    void shouldGenerateReplyAndSendIt() {
        when(generateAiReplyPort.generate(10L, MESSAGE.text())).thenReturn("Temos às 14h.");

        service.reply(MESSAGE);

        InOrder order = inOrder(generateAiReplyPort, sendMessagePort);
        order.verify(generateAiReplyPort).generate(10L, MESSAGE.text());
        order.verify(sendMessagePort).send(10L, "Temos às 14h.");
        // A publicação no Kafka está desligada no fluxo atual.
        verifyNoInteractions(publishEventPort);
    }

    @Test
    void shouldShowTypingWhileAiGeneratesAndStopBeforeSending() {
        SendMessagePort.TypingIndicator typing = mock(SendMessagePort.TypingIndicator.class);
        when(sendMessagePort.startTyping(10L)).thenReturn(typing);
        when(generateAiReplyPort.generate(10L, MESSAGE.text())).thenReturn("Temos às 14h.");

        service.reply(MESSAGE);

        InOrder order = inOrder(sendMessagePort, generateAiReplyPort, typing);
        order.verify(sendMessagePort).startTyping(10L);
        order.verify(generateAiReplyPort).generate(10L, MESSAGE.text());
        order.verify(typing).close();
        order.verify(sendMessagePort).send(10L, "Temos às 14h.");
    }

    @Test
    void shouldStopTypingWhenPhotoAnalysisFails() {
        SendMessagePort.TypingIndicator typing = mock(SendMessagePort.TypingIndicator.class);
        when(sendMessagePort.startTyping(10L)).thenReturn(typing);
        when(sendMessagePort.downloadFile("large")).thenReturn(IMAGE);
        when(generateAiReplyPort.analyzePhoto(10L, IMAGE)).thenThrow(new RuntimeException("timeout"));

        service.reply(PHOTO_MESSAGE);

        InOrder order = inOrder(typing, sendMessagePort);
        order.verify(typing).close();
        order.verify(sendMessagePort).send(10L, BellaConstants.AI_FAILURE_MESSAGE);
    }

    @Test
    void shouldNotShowTypingOnStartCommand() {
        IncomingMessage start = new IncomingMessage(10L, 20L, "maria", BellaConstants.TELEGRAM_START, Instant.now(), null, List.of());

        service.reply(start);

        verify(sendMessagePort, never()).startTyping(any());
    }

    @Test
    void shouldPassGuideComputedFromAnalysis() {
        var analysis = "Subtom: Frio — rosado\nEstação provável: Inverno Frio";
        when(sendMessagePort.downloadFile("large")).thenReturn(IMAGE);
        when(generateAiReplyPort.analyzePhoto(10L, IMAGE))
                .thenReturn(new PictureMessage(PhotoFaceStatus.CLEAR_SINGLE_FACE, analysis));
        when(generateAiReplyPort.generateConsultation(any(), any(), any())).thenReturn("Consultoria");

        service.reply(PHOTO_MESSAGE);

        verify(generateAiReplyPort).generateConsultation(10L, analysis, ConsultationGuide.forAnalysis(analysis));
    }

    @Test
    void shouldNotifyUserWhenAiFails() {
        when(generateAiReplyPort.generate(any(), any())).thenThrow(new RuntimeException("ollama fora do ar"));

        service.reply(MESSAGE);

        verify(sendMessagePort).send(10L, BellaConstants.AI_FAILURE_MESSAGE);
    }

    @Test
    void shouldNotifyUserWhenAiReplyIsBlank() {
        when(generateAiReplyPort.generate(any(), any())).thenReturn("  ");

        service.reply(MESSAGE);

        verify(sendMessagePort).send(10L, BellaConstants.AI_FAILURE_MESSAGE);
    }

    @Test
    void shouldSendInitialMessageOnStartCommand() {
        IncomingMessage start = new IncomingMessage(10L, 20L, "maria", BellaConstants.TELEGRAM_START, Instant.now(), null, List.of());

        service.reply(start);

        verify(sendMessagePort).send(10L, BellaConstants.INITIAL_MESSAGE);
        verifyNoInteractions(generateAiReplyPort);
    }

    @Test
    void shouldAnalyzeLargestPhotoAndReplyWithAnalysis() {
        when(sendMessagePort.downloadFile("large")).thenReturn(IMAGE);
        when(generateAiReplyPort.analyzePhoto(10L, IMAGE))
                .thenReturn(new PictureMessage(PhotoFaceStatus.CLEAR_SINGLE_FACE, ANALYSIS));
        when(generateAiReplyPort.generateConsultation(10L, ANALYSIS, GUIDE))
                .thenReturn("Seu subtom é quente; tons dourados combinam com você.");

        service.reply(PHOTO_MESSAGE);

        // A análise da foto passa pela conversa com a Bella antes de ser enviada.
        InOrder order = inOrder(generateAiReplyPort, sendMessagePort);
        order.verify(generateAiReplyPort).analyzePhoto(10L, IMAGE);
        order.verify(generateAiReplyPort).generateConsultation(10L, ANALYSIS, GUIDE);
        order.verify(sendMessagePort).send(10L, "Seu subtom é quente; tons dourados combinam com você.");

        ArgumentCaptor<MessageProcessedEvent> event = ArgumentCaptor.forClass(MessageProcessedEvent.class);
        verify(publishEventPort).publish(event.capture());
        assertThat(event.getValue().message()).isEqualTo(PHOTO_MESSAGE);
        assertThat(event.getValue().reply()).isEqualTo("Seu subtom é quente; tons dourados combinam com você.");
    }

    @Test
    void shouldNotPublishWhenPhotoAnalysisFails() {
        when(sendMessagePort.downloadFile("large")).thenReturn(IMAGE);
        when(generateAiReplyPort.analyzePhoto(10L, IMAGE)).thenThrow(new RuntimeException("timeout"));

        service.reply(PHOTO_MESSAGE);

        verify(sendMessagePort).send(10L, BellaConstants.AI_FAILURE_MESSAGE);
        verifyNoInteractions(publishEventPort);
    }

    @Test
    void shouldNotPublishWhenPhotoReplyIsBlank() {
        when(sendMessagePort.downloadFile("large")).thenReturn(IMAGE);
        when(generateAiReplyPort.analyzePhoto(10L, IMAGE))
                .thenReturn(new PictureMessage(PhotoFaceStatus.CLEAR_SINGLE_FACE, ANALYSIS));
        when(generateAiReplyPort.generateConsultation(10L, ANALYSIS, GUIDE)).thenReturn(" ");

        service.reply(PHOTO_MESSAGE);

        verify(sendMessagePort).send(10L, BellaConstants.AI_FAILURE_MESSAGE);
        verifyNoInteractions(publishEventPort);
    }

    @Test
    void shouldNotPublishWhenPhotoReplyDeliveryFails() {
        when(sendMessagePort.downloadFile("large")).thenReturn(IMAGE);
        when(generateAiReplyPort.analyzePhoto(10L, IMAGE))
                .thenReturn(new PictureMessage(PhotoFaceStatus.CLEAR_SINGLE_FACE, ANALYSIS));
        when(generateAiReplyPort.generateConsultation(10L, ANALYSIS, GUIDE)).thenReturn("Resposta");
        doThrow(new RuntimeException("telegram fora do ar")).when(sendMessagePort).send(10L, "Resposta");

        try {
            service.reply(PHOTO_MESSAGE);
        } catch (RuntimeException expected) {
            // A falha de envio sobe para o adapter de entrada, que só registra o erro.
        }

        verifyNoInteractions(publishEventPort);
    }

    @Test
    void shouldKeepFlowWhenPublishFails() {
        when(sendMessagePort.downloadFile("large")).thenReturn(IMAGE);
        when(generateAiReplyPort.analyzePhoto(10L, IMAGE))
                .thenReturn(new PictureMessage(PhotoFaceStatus.CLEAR_SINGLE_FACE, ANALYSIS));
        when(generateAiReplyPort.generateConsultation(10L, ANALYSIS, GUIDE)).thenReturn("Resposta");
        doThrow(new RuntimeException("kafka fora do ar")).when(publishEventPort).publish(any());

        service.reply(PHOTO_MESSAGE);

        verify(sendMessagePort).send(10L, "Resposta");
    }

    @Test
    void shouldRejectPhotoWithoutClearSingleFace() {
        when(sendMessagePort.downloadFile("large")).thenReturn(IMAGE);
        when(generateAiReplyPort.analyzePhoto(10L, IMAGE))
                .thenReturn(new PictureMessage(PhotoFaceStatus.MULTIPLE_FACES, null));
        when(generateAiReplyPort.generateInvalidPhotoReply(10L))
                .thenReturn("Não consegui ver um rosto só na foto. Pode enviar outra?");

        service.reply(PHOTO_MESSAGE);

        verify(generateAiReplyPort).generateInvalidPhotoReply(10L);
        verify(sendMessagePort).send(10L, "Não consegui ver um rosto só na foto. Pode enviar outra?");
    }

    @Test
    void shouldNotifyUserWhenPhotoDownloadFails() {
        when(sendMessagePort.downloadFile("large")).thenThrow(new RuntimeException("telegram fora do ar"));

        service.reply(PHOTO_MESSAGE);

        verify(sendMessagePort).send(10L, BellaConstants.AI_FAILURE_MESSAGE);
        verify(generateAiReplyPort, never()).analyzePhoto(any(), any());
        verifyNoInteractions(publishEventPort);
    }
}
