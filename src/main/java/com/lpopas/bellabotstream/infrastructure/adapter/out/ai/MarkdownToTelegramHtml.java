package com.lpopas.bellabotstream.infrastructure.adapter.out.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Converte Markdown no HTML aceito pelo Telegram (parse mode HTML).
 * O prompt (bella-system.md) pede Markdown, mas o modelo às vezes mistura HTML:
 * tags já válidas são preservadas e {@code <}, {@code >} e {@code &} soltos são escapados,
 * para a Bot API não rejeitar a mensagem.
 */
final class MarkdownToTelegramHtml {

    /** Marca temporária para trechos já convertidos; não aparece em texto gerado pelo modelo. */
    private static final char PLACEHOLDER = '\u0000';

    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\w+-]*[ \\t]*\\n?(.*?)```", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern LINE_BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TELEGRAM_TAG = Pattern.compile(
            "(?i)</?(b|strong|i|em|u|ins|s|strike|del|code|pre|blockquote|tg-spoiler)>|<a\\s+href=\"[^\"<>]*\">|</a>");
    private static final Pattern RESTORE = Pattern.compile(PLACEHOLDER + "(\\d+)" + PLACEHOLDER);
    private static final Pattern BARE_AMPERSAND = Pattern.compile("&(?!(?:amp|lt|gt|quot|#\\d+|#x[0-9a-fA-F]+);)");

    private static final Pattern HORIZONTAL_RULE = Pattern.compile("(?m)^[ \\t]*([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$\\n?");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]*#{1,6}[ \\t]+(.+?)[ \\t#]*$");
    private static final Pattern BULLET = Pattern.compile("(?m)^([ \\t]*)[-*+][ \\t]+");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern ITALIC_ASTERISK = Pattern.compile("(?<![*\\w])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![*\\w])");
    // Exige fronteira de palavra para não quebrar nomes como snake_case.
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("(?<![_\\w])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![_\\w])");
    private static final String QUOTE_PREFIX = "&gt;";

    private MarkdownToTelegramHtml() {
    }

    static String convert(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        var protectedParts = new ArrayList<String>();

        // 1. Código primeiro: o conteúdo é escapado e não passa pelas demais regras.
        var result = protect(text, CODE_BLOCK, protectedParts, m -> "<pre>" + escape(m.group(1).stripTrailing()) + "</pre>");
        result = protect(result, INLINE_CODE, protectedParts, m -> "<code>" + escape(m.group(1)) + "</code>");

        // 2. Tags que o Telegram aceita ficam como estão; <br> não é aceito e vira quebra de linha.
        result = LINE_BREAK_TAG.matcher(result).replaceAll("\n");
        result = protect(result, TELEGRAM_TAG, protectedParts, MatchResult::group);

        // 3. O que sobrou é texto: escapa antes de gerar as tags do Markdown.
        result = escape(result);

        // 4. Markdown de bloco (linhas) e depois o inline.
        result = HORIZONTAL_RULE.matcher(result).replaceAll("");
        result = HEADING.matcher(result).replaceAll("<b>$1</b>");
        result = convertBlockquotes(result);
        result = BULLET.matcher(result).replaceAll("$1• ");
        result = LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(
                "<a href=\"" + m.group(2).replace("\"", "%22") + "\">" + m.group(1) + "</a>"));
        result = BOLD.matcher(result).replaceAll("<b>$2</b>");
        result = STRIKETHROUGH.matcher(result).replaceAll("<s>$1</s>");
        result = ITALIC_ASTERISK.matcher(result).replaceAll("<i>$1</i>");
        result = ITALIC_UNDERSCORE.matcher(result).replaceAll("<i>$1</i>");

        // 5. Devolve os trechos protegidos.
        return RESTORE.matcher(result)
                .replaceAll(m -> Matcher.quoteReplacement(protectedParts.get(Integer.parseInt(m.group(1)))))
                .strip();
    }

    private static String protect(String text, Pattern pattern, List<String> parts, Function<MatchResult, String> html) {
        return pattern.matcher(text).replaceAll(m -> {
            parts.add(html.apply(m));
            return Matcher.quoteReplacement(PLACEHOLDER + String.valueOf(parts.size() - 1) + PLACEHOLDER);
        });
    }

    /** Linhas seguidas começando com "> " viram um único {@code <blockquote>}. */
    private static String convertBlockquotes(String text) {
        var out = new StringBuilder();
        var quote = new ArrayList<String>();
        for (var line : text.split("\n", -1)) {
            var trimmed = line.stripLeading();
            if (trimmed.startsWith(QUOTE_PREFIX)) {
                quote.add(trimmed.substring(QUOTE_PREFIX.length()).stripLeading());
                continue;
            }
            flushQuote(out, quote);
            out.append(line).append('\n');
        }
        flushQuote(out, quote);
        return out.substring(0, out.length() - 1);
    }

    private static void flushQuote(StringBuilder out, List<String> quote) {
        if (!quote.isEmpty()) {
            out.append("<blockquote>").append(String.join("\n", quote)).append("</blockquote>\n");
            quote.clear();
        }
    }

    private static String escape(String text) {
        return BARE_AMPERSAND.matcher(text).replaceAll("&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
