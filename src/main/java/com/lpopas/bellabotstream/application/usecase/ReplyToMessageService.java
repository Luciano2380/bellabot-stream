package com.lpopas.bellabotstream.application.usecase;

import com.lpopas.bellabotstream.application.port.in.ReplyToMessageUseCase;
import com.lpopas.bellabotstream.application.port.out.GenerateAiReplyPort;
import com.lpopas.bellabotstream.application.port.out.PublishEventPort;
import com.lpopas.bellabotstream.application.port.out.SendMessagePort;
import com.lpopas.bellabotstream.domain.constants.BellaConstants;
import com.lpopas.bellabotstream.domain.event.MessageProcessedEvent;
import com.lpopas.bellabotstream.domain.model.IncomingMessage;
import com.lpopas.bellabotstream.domain.model.PictureMessage;
import com.lpopas.bellabotstream.domain.model.enuns.PhotoFaceStatus;
import com.lpopas.bellabotstream.domain.service.ConsultationGuide;
import com.lpopas.bellabotstream.domain.valueobject.FileContent;
import com.lpopas.bellabotstream.domain.valueobject.Photo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fluxo de texto: gera a resposta com a LLM -> responde no Telegram.
 * Fluxo de foto: baixa a maior resolução -> analisa com a LLM -> responde no Telegram -> publica o evento.
 * Só é publicado o que foi processado com sucesso: a IA respondeu e a mensagem foi entregue ao usuário.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyToMessageService implements ReplyToMessageUseCase {

    private final GenerateAiReplyPort generateAiReplyPort;
    private final SendMessagePort sendMessagePort;
    private final PublishEventPort publishEventPort;

    @Override
    public void reply(IncomingMessage message) {
        message.largestPhoto().ifPresentOrElse(
                photo -> replyToPhoto(message, photo),
                () -> replyToText(message));
    }

    private void replyToText(IncomingMessage message) {
        String reply = null;
        if(message.text().equals(BellaConstants.TELEGRAM_START)){
            log.info("Comando /start recebido; enviando mensagem inicial");
            sendMessagePort.send(message.chatId(), BellaConstants.INITIAL_MESSAGE);
        } else {
            long start = System.nanoTime();
            try (var typing = sendMessagePort.startTyping(message.chatId())) {
                reply = generateAiReplyPort.generate(message.chatId(), message.text());
                log.info("Resposta da IA gerada em {} ms (tamanho={})", elapsedMillis(start), reply.length());
            } catch (RuntimeException e) {
                log.error("Falha ao gerar resposta da IA para chatId={} após {} ms",
                        message.chatId(), elapsedMillis(start), e);
            }

            if (reply == null || reply.isBlank()) {
                log.warn("Enviando mensagem padrão de falha ao chatId={}", message.chatId());
                reply = BellaConstants.AI_FAILURE_MESSAGE;
            }
            sendMessagePort.send(message.chatId(), reply);
            log.debug("Resposta de texto enviada ao chatId={}", message.chatId());
        }
    }

    private void replyToPhoto(IncomingMessage message, Photo photo) {
        log.info("Processando foto {}x{} (fileId={})", photo.width(), photo.height(), photo.fileId());
        String reply = null;
        long start = System.nanoTime();
        try (var typing = sendMessagePort.startTyping(message.chatId())) {
            FileContent image = downloadPhoto(photo);
            log.debug("Foto baixada: {} bytes ({}) em {} ms", image.size(), image.mimeType(), elapsedMillis(start));

            long analysisStart = System.nanoTime();
            PictureMessage picture = generateAiReplyPort.analyzePhoto(message.chatId(), image);
            log.info("Análise da foto concluída em {} ms: status={}", elapsedMillis(analysisStart), picture.photoFaceStatus());
            reply = replyToAnalysis(message.chatId(), picture);
        } catch (RuntimeException e) {
            log.error("Falha ao analisar foto para chatId={} após {} ms", message.chatId(), elapsedMillis(start), e);
        }
        if (reply == null || reply.isBlank()) {
            log.warn("Resposta da foto vazia ou com falha; enviando mensagem padrão ao chatId={}", message.chatId());
            sendMessagePort.send(message.chatId(), BellaConstants.AI_FAILURE_MESSAGE);
            return;
        }
        sendMessagePort.send(message.chatId(), reply);
        log.debug("Resposta da foto enviada ao chatId={}", message.chatId());
        publish(MessageProcessedEvent.of(message, reply));
    }

    private FileContent downloadPhoto(Photo photo) {
        FileContent file = sendMessagePort.downloadFile(photo.fileId());
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("Arquivo vazio para fileId=" + photo.fileId());
        }
        return file;
    }

    /**
     * Foto inválida: a Bella pede outra. Foto válida: as orientações (base, cores, intensidade, cuidados)
     * são calculadas pelo {@link ConsultationGuide} e vão prontas para a Bella, que só redige a consultoria.
     */
    private String replyToAnalysis(Long chatId, PictureMessage picture) {
        if (picture.photoFaceStatus() != PhotoFaceStatus.CLEAR_SINGLE_FACE) {
            log.info("Foto rejeitada: status={}", picture.photoFaceStatus());
            return generateAiReplyPort.generateInvalidPhotoReply(chatId);
        }
        if (picture.message() == null || picture.message().isBlank()) {
            throw new IllegalStateException("Análise de foto válida sem mensagem");
        }
        return generateAiReplyPort.generateConsultation(
                chatId, picture.message(), ConsultationGuide.forAnalysis(picture.message()));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void publish(MessageProcessedEvent event) {
        try {
            publishEventPort.publish(event);
        } catch (RuntimeException e) {
            // O usuário já recebeu a resposta; a falha de publicação não deve interromper o fluxo.
            log.error("Falha ao publicar evento {} (chatId={})", event.eventId(), event.message().chatId(), e);
        }
    }
}
