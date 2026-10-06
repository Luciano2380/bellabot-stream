package com.lpopas.bellabotstream.infrastructure.adapter.in.telegram;

import com.lpopas.bellabotstream.application.port.in.ReplyToMessageUseCase;
import com.lpopas.bellabotstream.domain.model.IncomingMessage;
import com.lpopas.bellabotstream.domain.valueobject.Photo;
import com.lpopas.bellabotstream.infrastructure.config.telegram.TelegramBotProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.AfterBotRegistration;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter de entrada do Telegram (long polling). Registrado automaticamente pelo
 * telegrambots-springboot-longpolling-starter por ser um {@link SpringLongPollingBot}.
 * Cada update roda em uma virtual thread para que chamadas lentas à LLM não bloqueiem outros chats.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BellaBotTelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private static final String MDC_UPDATE_ID = "updateId";
    private static final String MDC_CHAT_ID = "chatId";

    private final TelegramBotProperties props;
    private final ReplyToMessageUseCase replyToMessageUseCase;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public String getBotToken() {
        return props.token();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(List<Update> updates) {
        updates.forEach(update -> executor.execute(() -> onUpdateReceived(update)));
    }

    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) {
            log.debug("Update {} ignorado: não contém mensagem", update.getUpdateId());
            return;
        }
        Message message = update.getMessage();
        if (!message.hasText() && !message.hasPhoto()) {
            log.debug("Update {} ignorado: mensagem sem texto nem foto (chatId={})", update.getUpdateId(), message.getChatId());
            return;
        }
        // Correlaciona todos os logs deste update (impressos via logging.pattern.correlation).
        MDC.put(MDC_UPDATE_ID, String.valueOf(update.getUpdateId()));
        MDC.put(MDC_CHAT_ID, String.valueOf(message.getChatId()));
        long start = System.nanoTime();
        try {
            log.info("Mensagem recebida: tipo={}, tamanhoTexto={}",
                    message.hasPhoto() ? "foto" : "texto", textLength(message));
            replyToMessageUseCase.reply(toIncomingMessage(message));
            log.info("Update processado em {} ms", elapsedMillis(start));
        } catch (RuntimeException e) {
            log.error("Erro ao processar update {} (chatId={}) após {} ms",
                    update.getUpdateId(), message.getChatId(), elapsedMillis(start), e);
        } finally {
            MDC.remove(MDC_UPDATE_ID);
            MDC.remove(MDC_CHAT_ID);
        }
    }

    private static int textLength(Message message) {
        String text = message.hasText() ? message.getText() : message.getCaption();
        return text != null ? text.length() : 0;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static IncomingMessage toIncomingMessage(Message message) {
        User from = message.getFrom();
        return new IncomingMessage(
                message.getChatId(),
                from != null ? from.getId() : null,
                from != null ? from.getUserName() : null,
                // Mensagens com foto trazem o texto na legenda.
                message.hasText() ? message.getText() : message.getCaption(),
                Instant.ofEpochSecond(message.getDate()),
                message.hasContact() ? message.getContact().getPhoneNumber() : null,
                toPhotos(message));
    }

    private static List<Photo> toPhotos(Message message) {
        if (!message.hasPhoto()) {
            return List.of();
        }
        return message.getPhoto().stream()
                .map(size -> new Photo(size.getFileId(), nullToZero(size.getWidth()), nullToZero(size.getHeight())))
                .toList();
    }

    private static int nullToZero(Integer value) {
        return value != null ? value : 0;
    }

    @AfterBotRegistration
    public void afterRegistration() {
        log.info("Bot @{} registrado e escutando mensagens (long polling)", props.username());
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdown();
    }
}
