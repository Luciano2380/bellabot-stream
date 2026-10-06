package com.lpopas.bellabotstream.infrastructure.adapter.out.telegram;

import com.lpopas.bellabotstream.application.exception.MessageDeliveryException;
import com.lpopas.bellabotstream.application.port.out.SendMessagePort;
import com.lpopas.bellabotstream.domain.valueobject.FileContent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMessageSender implements SendMessagePort {

    /** Limite de caracteres por mensagem da Bot API. */
    static final int TELEGRAM_MAX_LENGTH = 4096;

    private static final Pattern HTML_TAG = Pattern.compile(
            "(?i)</?(b|strong|i|em|u|ins|s|strike|del|code|pre|blockquote|tg-spoiler|a)(\\s[^>]*)?>");

    /** O Telegram converte toda foto (não enviada como documento) para JPEG; vale quando a extensão não indica outro tipo. */
    static final String DEFAULT_MIME_TYPE = "image/jpeg";

    /** O Telegram apaga o "digitando..." após ~5s; reenviar a cada 4s o mantém visível sem intervalos. */
    static final long TYPING_REFRESH_SECONDS = 4;

    private final TelegramClient telegramClient;

    /**
     * Uma só thread basta: ela apenas dispara o executeAsync (HTTP assíncrono do OkHttp), sem bloquear.
     * Daemon para não segurar o desligamento da JVM.
     */
    private final ScheduledExecutorService typingScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("telegram-typing").daemon().factory());

    @Override
    public void send(Long chatId, String text) {
        List<String> parts = split(text, TELEGRAM_MAX_LENGTH);
        log.debug("Enviando mensagem ao chatId={}: {} caracteres em {} parte(s)", chatId, text.length(), parts.size());
        parts.forEach(part -> sendChunk(chatId, part));
    }

    /**
     * Divide o HTML em partes de até {@code maxLength} caracteres, cada uma com HTML válido.
     * O corte é feito de preferência numa linha em branco, depois numa quebra de linha e depois num espaço,
     * nunca dentro de uma tag ou entidade. Tags abertas no ponto de corte (ex.: {@code <b>}) são fechadas
     * no fim da parte e reabertas no início da seguinte; sem isso, a Bot API rejeita as duas partes.
     */
    static List<String> split(String html, int maxLength) {
        var parts = new ArrayList<String>();
        var text = html;
        while (text.length() > maxLength) {
            int limit = maxLength;
            String part;
            Deque<OpenTag> openTags;
            String closing;
            do {
                int cut = findCut(text, limit);
                part = text.substring(0, cut);
                openTags = openTagsAt(part);
                closing = closingTags(openTags);
                limit = maxLength - closing.length();
            } while (part.length() + closing.length() > maxLength);

            parts.add(part.stripTrailing() + closing);
            text = reopeningTags(openTags) + text.substring(part.length()).stripLeading();
        }
        if (!text.isBlank()) {
            parts.add(text);
        }
        return parts;
    }

    private static int findCut(String text, int limit) {
        // Evita partes muito pequenas: um separador no início do texto não serve como ponto de corte.
        int minimum = limit / 2;
        for (String separator : List.of("\n\n", "\n", " ")) {
            int index = text.lastIndexOf(separator, limit - separator.length());
            while (index > minimum && insideTagOrEntity(text, index)) {
                index = text.lastIndexOf(separator, index - 1);
            }
            if (index > minimum) {
                return index + separator.length();
            }
        }
        // Sem separador: corte seco no limite, recuando para antes de uma tag ou entidade incompleta.
        int cut = limit;
        int tagStart = text.lastIndexOf('<', cut - 1);
        if (tagStart > text.lastIndexOf('>', cut - 1)) {
            cut = tagStart;
        }
        int entityStart = text.lastIndexOf('&', cut - 1);
        if (entityStart > text.lastIndexOf(';', cut - 1) && cut - entityStart <= 10) {
            cut = entityStart;
        }
        return cut > 0 ? cut : limit;
    }

    private static boolean insideTagOrEntity(String text, int index) {
        boolean insideTag = text.lastIndexOf('<', index) > text.lastIndexOf('>', index);
        int entityStart = text.lastIndexOf('&', index);
        boolean insideEntity = entityStart > text.lastIndexOf(';', index) && index - entityStart <= 10;
        return insideTag || insideEntity;
    }

    /** Tags ainda abertas no fim do trecho, na ordem em que foram abertas. */
    private static Deque<OpenTag> openTagsAt(String html) {
        Deque<OpenTag> open = new ArrayDeque<>();
        Matcher matcher = HTML_TAG.matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            String name = matcher.group(1).toLowerCase();
            if (tag.startsWith("</")) {
                // Fecha a ocorrência mais recente com o mesmo nome.
                for (var it = open.descendingIterator(); it.hasNext(); ) {
                    if (it.next().name().equals(name)) {
                        it.remove();
                        break;
                    }
                }
            } else {
                open.addLast(new OpenTag(name, tag));
            }
        }
        return open;
    }

    private static String closingTags(Deque<OpenTag> open) {
        var closing = new StringBuilder();
        open.descendingIterator().forEachRemaining(tag -> closing.append("</").append(tag.name()).append('>'));
        return closing.toString();
    }

    private static String reopeningTags(Deque<OpenTag> open) {
        var opening = new StringBuilder();
        open.forEach(tag -> opening.append(tag.openingTag()));
        return opening.toString();
    }

    private record OpenTag(String name, String openingTag) {
    }

    /**
     * O texto chega em HTML do Telegram (convertido do Markdown do modelo). Se ainda assim for inválido (tag não fechada, "<" solto),
     * a Bot API rejeita a mensagem; nesse caso, reenvia como texto puro, sem as tags.
     */
    private void sendChunk(Long chatId, String chunk) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(chunk).parseMode(ParseMode.HTML).build());
        } catch (TelegramApiException e) {
            log.warn("Telegram rejeitou a mensagem em HTML para chatId={}; reenviando como texto puro: {}",
                    chatId, e.getMessage());
            try {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text(toPlainText(chunk)).build());
            } catch (TelegramApiException fallbackError) {
                throw new MessageDeliveryException("Falha ao enviar mensagem ao chatId=" + chatId, fallbackError);
            }
        }
    }

    static String toPlainText(String html) {
        return HTML_TAG.matcher(html).replaceAll("")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    @Override
    public TypingIndicator startTyping(Long chatId) {
        var action = SendChatAction.builder().chatId(chatId).action(ActionType.TYPING.toString()).build();
        var task = typingScheduler.scheduleAtFixedRate(() -> sendTyping(chatId, action),
                0, TYPING_REFRESH_SECONDS, TimeUnit.SECONDS);
        return () -> task.cancel(false);
    }

    /** O indicador é cosmético: falhas só geram log e não interrompem o processamento. */
    private void sendTyping(Long chatId, SendChatAction action) {
        try {
            telegramClient.executeAsync(action).exceptionally(e -> {
                log.debug("Falha ao enviar \"digitando...\" ao chatId={}: {}", chatId, e.getMessage());
                return null;
            });
        } catch (TelegramApiException | RuntimeException e) {
            log.debug("Falha ao enviar \"digitando...\" ao chatId={}: {}", chatId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdownTypingScheduler() {
        typingScheduler.shutdownNow();
    }

    @Override
    public FileContent downloadFile(String fileId) {
        // O download usa o próprio TelegramClient: respeita a apiUrl configurada e não expõe o token em URLs.
        try {
            File file = telegramClient.execute(new GetFile(fileId));
            try (InputStream inputStream = telegramClient.downloadFileAsStream(file)) {
                byte[] bytes = inputStream.readAllBytes();
                String mimeType = mimeTypeOf(file.getFilePath());
                log.debug("Arquivo fileId={} baixado: {} bytes ({})", fileId, bytes.length, mimeType);
                return new FileContent(bytes, mimeType);
            }
        } catch (TelegramApiException | IOException e) {
            throw new MessageDeliveryException("Falha ao baixar o arquivo fileId=" + fileId, e);
        }
    }

    /** Tipo do arquivo pela extensão do caminho devolvido pelo Telegram (ex.: photos/file_1.jpg). */
    static String mimeTypeOf(String filePath) {
        if (filePath == null) {
            return DEFAULT_MIME_TYPE;
        }
        String path = filePath.toLowerCase(Locale.ROOT);
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        return DEFAULT_MIME_TYPE;
    }
}
