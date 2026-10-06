package com.lpopas.bellabotstream.infrastructure.adapter.out.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownToTelegramHtmlTest {

    @Test
    void shouldConvertBoldItalicAndStrikethrough() {
        assertThat(MarkdownToTelegramHtml.convert("**Base** e __pó__, *blush* e _batom_, ~~sem estoque~~"))
                .isEqualTo("<b>Base</b> e <b>pó</b>, <i>blush</i> e <i>batom</i>, <s>sem estoque</s>");
    }

    @Test
    void shouldConvertHeadingsAndBulletsAndRemoveHorizontalRules() {
        var markdown = """
                ## Sua análise
                ---
                * Limpe a pele
                - Hidrate
                + Use protetor
                1. Primeiro passo
                """;

        assertThat(MarkdownToTelegramHtml.convert(markdown)).isEqualTo("""
                <b>Sua análise</b>
                • Limpe a pele
                • Hidrate
                • Use protetor
                1. Primeiro passo""");
    }

    @Test
    void shouldConvertLinks() {
        assertThat(MarkdownToTelegramHtml.convert("Veja o [Batom Cremoso](https://loja.marykay.com.br/batom/p?a=1&b=2)"))
                .isEqualTo("Veja o <a href=\"https://loja.marykay.com.br/batom/p?a=1&amp;b=2\">Batom Cremoso</a>");
    }

    @Test
    void shouldGroupBlockquoteLines() {
        var markdown = """
                Contato:
                > **Consultora Mary Kay**
                > Fulana de Tal
                Obrigada!""";

        assertThat(MarkdownToTelegramHtml.convert(markdown)).isEqualTo("""
                Contato:
                <blockquote><b>Consultora Mary Kay</b>
                Fulana de Tal</blockquote>
                Obrigada!""");
    }

    @Test
    void shouldEscapeCodeAndNotFormatInsideIt() {
        assertThat(MarkdownToTelegramHtml.convert("Use `**a** < b`"))
                .isEqualTo("Use <code>**a** &lt; b</code>");
        assertThat(MarkdownToTelegramHtml.convert("```java\nif (a < b && *c*) {}\n```"))
                .isEqualTo("<pre>if (a &lt; b &amp;&amp; *c*) {}</pre>");
    }

    @Test
    void shouldKeepValidTelegramTagsAndEscapeLooseSymbols() {
        assertThat(MarkdownToTelegramHtml.convert("<b>Dica</b>: pele seca & oleosa <3, use <i>sérum</i>"))
                .isEqualTo("<b>Dica</b>: pele seca &amp; oleosa &lt;3, use <i>sérum</i>");
        assertThat(MarkdownToTelegramHtml.convert("<a href=\"https://x.com\">loja</a> R$ 10 &amp; frete"))
                .isEqualTo("<a href=\"https://x.com\">loja</a> R$ 10 &amp; frete");
    }

    @Test
    void shouldReplaceBrTagsAndEscapeUnsupportedTags() {
        assertThat(MarkdownToTelegramHtml.convert("Linha 1<br>Linha 2<br/><h1>Título</h1>"))
                .isEqualTo("Linha 1\nLinha 2\n&lt;h1&gt;Título&lt;/h1&gt;");
    }

    @Test
    void shouldNotTreatUnderscoresInsideWordsOrLoneAsterisksAsItalic() {
        assertThat(MarkdownToTelegramHtml.convert("arquivo bella_system_prompt e 5 * 3 * 2"))
                .isEqualTo("arquivo bella_system_prompt e 5 * 3 * 2");
    }

    @Test
    void shouldHandleNullAndBlank() {
        assertThat(MarkdownToTelegramHtml.convert(null)).isEmpty();
        assertThat(MarkdownToTelegramHtml.convert("   ")).isEqualTo("   ");
    }
}
