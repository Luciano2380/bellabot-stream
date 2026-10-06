package com.lpopas.bellabotstream.infrastructure.adapter.out.telegram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramMessageSenderTest {

    private final TelegramClient telegramClient = mock(TelegramClient.class);
    private final TelegramMessageSender sender = new TelegramMessageSender(telegramClient);

    @AfterEach
    void tearDown() {
        sender.shutdownTypingScheduler();
    }

    private static final Pattern TAG = Pattern.compile("</?(b|i|a)(\\s[^>]*)?>");

    @Test
    void shouldInferMimeTypeFromTelegramFilePath() {
        assertThat(TelegramMessageSender.mimeTypeOf("photos/file_1.jpg")).isEqualTo("image/jpeg");
        assertThat(TelegramMessageSender.mimeTypeOf("documents/foto.PNG")).isEqualTo("image/png");
        assertThat(TelegramMessageSender.mimeTypeOf("stickers/a.webp")).isEqualTo("image/webp");
        assertThat(TelegramMessageSender.mimeTypeOf(null)).isEqualTo(TelegramMessageSender.DEFAULT_MIME_TYPE);
    }

    @Test
    void shouldNotSplitShortMessage() {
        assertThat(TelegramMessageSender.split("<b>Oi</b>", 4096)).containsExactly("<b>Oi</b>");
    }

    @Test
    void shouldSplitAtBlankLineBeforeLimit() {
        var first = "a".repeat(30);
        var second = "b".repeat(30);

        assertThat(TelegramMessageSender.split(first + "\n\n" + second, 50)).containsExactly(first, second);
    }

    @Test
    void shouldCloseAndReopenTagsAcrossParts() {
        var html = "<b>" + "palavra ".repeat(20) + "</b> fim";

        List<String> parts = TelegramMessageSender.split(html, 60);

        assertThat(parts).hasSizeGreaterThan(1).allSatisfy(part -> {
            assertThat(part.length()).isLessThanOrEqualTo(60);
            assertThat(balanced(part)).as("tags equilibradas em: %s", part).isTrue();
        });
        assertThat(parts.get(0)).startsWith("<b>").endsWith("</b>");
        assertThat(parts.get(1)).startsWith("<b>");
        assertThat(String.join(" ", parts).replaceAll("</?b>", "").replaceAll("\\s+", " ").strip())
                .isEqualTo(html.replaceAll("</?b>", "").replaceAll("\\s+", " ").strip());
    }

    @Test
    void shouldReopenLinkWithItsHref() {
        var html = "<a href=\"https://loja.marykay.com.br/base/p\">" + "texto ".repeat(15) + "</a>";

        List<String> parts = TelegramMessageSender.split(html, 70);

        assertThat(parts).allSatisfy(part -> assertThat(balanced(part)).isTrue());
        assertThat(parts.get(1)).startsWith("<a href=\"https://loja.marykay.com.br/base/p\">");
    }

    @Test
    void shouldNotCutInsideTagOrEntityWithoutSeparators() {
        var html = "x".repeat(45) + "&amp;" + "<b>y</b>" + "z".repeat(40);

        List<String> parts = TelegramMessageSender.split(html, 50);

        assertThat(parts).allSatisfy(part -> {
            assertThat(part.length()).isLessThanOrEqualTo(50);
            assertThat(balanced(part)).isTrue();
            assertThat(part).doesNotEndWith("&").doesNotEndWith("&am").doesNotContain("<b</b>");
        });
        assertThat(String.join("", parts).replaceAll("</?b>", "")).isEqualTo(html.replaceAll("</?b>", ""));
    }

    /** Toda tag aberta é fechada na mesma parte, e nenhuma é fechada sem ter sido aberta. */
    private static boolean balanced(String html) {
        var open = new java.util.ArrayDeque<String>();
        var matcher = TAG.matcher(html);
        while (matcher.find()) {
            if (matcher.group().startsWith("</")) {
                if (open.isEmpty() || !open.pop().equals(matcher.group(1))) {
                    return false;
                }
            } else {
                open.push(matcher.group(1));
            }
        }
        return open.isEmpty();
    }

    @Test
    void shouldSendTypingImmediatelyAndStopAfterClose() throws Exception {
        when(telegramClient.executeAsync(any(SendChatAction.class))).thenReturn(CompletableFuture.completedFuture(true));

        var typing = sender.startTyping(10L);
        ArgumentCaptor<SendChatAction> action = ArgumentCaptor.forClass(SendChatAction.class);
        verify(telegramClient, timeout(1000)).executeAsync(action.capture());
        typing.close();

        assertThat(action.getValue().getChatId()).isEqualTo("10");
        assertThat(action.getValue().getAction()).isEqualTo(ActionType.TYPING.toString());
        // Fechado antes do próximo ciclo (4s): nenhum reenvio depois disso.
        verify(telegramClient, after(TelegramMessageSender.TYPING_REFRESH_SECONDS * 1000 + 500).times(1))
                .executeAsync(any(SendChatAction.class));
    }

    @Test
    void shouldIgnoreTypingFailures() throws Exception {
        when(telegramClient.executeAsync(any(SendChatAction.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("telegram fora do ar")));

        try (var typing = sender.startTyping(10L)) {
            verify(telegramClient, timeout(1000).times(1)).executeAsync(any(SendChatAction.class));
        }
    }
}
