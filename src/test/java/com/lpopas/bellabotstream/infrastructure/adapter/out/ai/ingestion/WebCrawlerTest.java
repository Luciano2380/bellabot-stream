package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.ingestion;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WebCrawlerTest {

    private static final URI SEED = URI.create("https://www.sbd.org.br/cuidados");

    @Test
    void shouldNormalizeFragmentTrailingSlashAndCase() {
        assertThat(WebCrawler.normalize("HTTPS://WWW.SBD.org.br/cuidados/pele/#topo"))
                .contains(URI.create("https://www.sbd.org.br/cuidados/pele"));
        assertThat(WebCrawler.normalize("https://www.sbd.org.br"))
                .contains(URI.create("https://www.sbd.org.br/"));
        assertThat(WebCrawler.normalize("https://loja.com/busca?q=batom#x"))
                .contains(URI.create("https://loja.com/busca?q=batom"));
    }

    @Test
    void shouldRejectNonHttpUrls() {
        assertThat(WebCrawler.normalize("mailto:contato@sbd.org.br")).isEqualTo(Optional.empty());
        assertThat(WebCrawler.normalize("tel:+551199999999")).isEqualTo(Optional.empty());
        assertThat(WebCrawler.normalize("javascript:void(0)")).isEqualTo(Optional.empty());
        assertThat(WebCrawler.normalize("")).isEqualTo(Optional.empty());
    }

    @Test
    void shouldKeepOnlyLinksOnSameHostBelowSeedPath() {
        assertThat(WebCrawler.isInScope(URI.create("https://www.sbd.org.br/cuidados"), SEED)).isTrue();
        assertThat(WebCrawler.isInScope(URI.create("https://www.sbd.org.br/cuidados/pele"), SEED)).isTrue();
        assertThat(WebCrawler.isInScope(URI.create("https://www.sbd.org.br/cuidados-extras"), SEED)).isFalse();
        assertThat(WebCrawler.isInScope(URI.create("https://www.sbd.org.br/noticias"), SEED)).isFalse();
        assertThat(WebCrawler.isInScope(URI.create("https://outro.org.br/cuidados/pele"), SEED)).isFalse();
        assertThat(WebCrawler.isInScope(URI.create("https://www.sbd.org.br/cuidados/guia.pdf"), SEED)).isFalse();
        assertThat(WebCrawler.isInScope(URI.create("https://www.sbd.org.br/qualquer"), URI.create("https://www.sbd.org.br/")))
                .isTrue();
    }

    @Test
    void shouldExtractAbsoluteInScopeLinks() {
        var html = Jsoup.parse("""
                <a href="/cuidados/pele/">Pele</a>
                <a href="pele#topo">Pele de novo</a>
                <a href="https://www.sbd.org.br/noticias">Notícias</a>
                <a href="mailto:a@b.c">E-mail</a>
                """, "https://www.sbd.org.br/cuidados/");

        assertThat(WebCrawler.extractLinks(html, SEED)).containsExactly(
                URI.create("https://www.sbd.org.br/cuidados/pele"),
                URI.create("https://www.sbd.org.br/cuidados/pele"));
    }

    @Test
    void shouldParseDisallowRulesOnlyForWildcardAgent() {
        var robots = """
                User-agent: Googlebot
                Disallow: /privado-google/

                User-agent: *
                Disallow: /wp-admin/
                Disallow: /wp-login.php # login
                Allow: /wp-admin/admin-ajax.php
                Disallow:
                """;

        var disallowed = WebCrawler.parseDisallowedPaths(robots);

        assertThat(disallowed).containsExactly("/wp-admin/", "/wp-login.php");
        assertThat(WebCrawler.isDisallowed("/wp-admin/options.php", disallowed)).isTrue();
        assertThat(WebCrawler.isDisallowed("/cuidados/pele", disallowed)).isFalse();
    }

    @Test
    void shouldGroupConsecutiveUserAgentLines() {
        var robots = """
                User-agent: Googlebot
                User-agent: *
                Disallow: /img/
                """;

        assertThat(WebCrawler.parseDisallowedPaths(robots)).isEqualTo(List.of("/img/"));
    }

    @Test
    void shouldNotDuplicateTextOfNestedMatches() {
        var html = Jsoup.parse("""
                <main><article><p>Use protetor solar.</p><p>Hidrate a pele.</p></article></main>
                <aside><p>Leia também</p></aside>
                """);

        assertThat(WebCrawler.extractText(html)).isEqualTo("Use protetor solar. Hidrate a pele. Leia também");
    }

    @Test
    void shouldFallBackToBodyTextWhenNoContentSelectorMatches() {
        var html = Jsoup.parse("<html><body><div>Proteja a pele do sol</div></body></html>");

        assertThat(WebCrawler.extractText(html)).isEqualTo("Proteja a pele do sol");
    }
}
