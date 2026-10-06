package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.ingestion;

import com.lpopas.bellabotstream.infrastructure.config.ai.RagProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Crawler em largura (BFS) limitado ao host e ao caminho da URL semente.
 * Páginas sem texto no HTML estático são renderizadas com Playwright, reaproveitando um único Chromium por crawl.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebCrawler {

    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    static final String CSS_QUERY = "article, main, p, .feed-post-body, .materia-conteudo";
    static final String DYNAMIC_CONTENT_SELECTOR = "#gallery-layout-container";
    static final double DYNAMIC_PAGE_TIMEOUT_MS = 15_000;
    static final int HTTP_TIMEOUT_MS = 15_000;

    private static final Pattern NON_HTML_PATH = Pattern.compile(
            "(?i).*\\.(pdf|jpe?g|png|gif|webp|svg|ico|zip|rar|gz|mp3|mp4|avi|mov|docx?|xlsx?|pptx?|css|js|json|xml|txt)$");

    private final RagProperties ragProperties;

    /**
     * Percorre o site a partir de {@code seedUrl} e entrega cada página com texto ao {@code onPage}.
     * Falhas em uma página (inclusive no {@code onPage}) só geram log; o crawl continua.
     */
    public void crawl(String seedUrl, Consumer<CrawledPage> onPage) {
        var site = ragProperties.site();
        var start = System.nanoTime();

        Optional<URI> seed = normalize(seedUrl);
        if (seed.isEmpty()) {
            log.error("URL semente inválida: {}", seedUrl);
            return;
        }
        var seedUri = seed.get();
        var disallowed = fetchDisallowedPaths(seedUri);
        log.info("Iniciando crawl de {} (profundidade máx. {}, máx. {} páginas, intervalo {} ms, {} regras Disallow)",
                seedUri, site.maxDepth(), site.maxPages(), site.requestDelay().toMillis(), disallowed.size());

        var queue = new ArrayDeque<CrawledLink>();
        var visited = new HashSet<String>();
        var seenTexts = new HashSet<String>();
        queue.add(new CrawledLink(seedUri, 0));
        visited.add(seedUri.toString());

        int fetched = 0;
        int ingested = 0;
        int skipped = 0;
        int failed = 0;

        try (var renderer = new DynamicRenderer()) {
            while (!queue.isEmpty() && fetched < site.maxPages()) {
                var link = queue.poll();
                var url = link.uri().toString();

                if (isDisallowed(link.uri().getPath(), disallowed)) {
                    log.warn("Página bloqueada pelo robots.txt; ignorada: {}", url);
                    skipped++;
                    continue;
                }
                if (fetched > 0 && !pause(site.requestDelay())) {
                    log.warn("Crawl de {} interrompido", seedUri);
                    break;
                }
                fetched++;

                try {
                    var html = Jsoup.connect(url).userAgent(USER_AGENT).timeout(HTTP_TIMEOUT_MS).get();
                    var text = extractText(html);
                    // Páginas renderizadas por JavaScript chegam sem texto no HTML estático.
                    if (text.isBlank()) {
                        html = Jsoup.parse(renderer.render(url), url);
                        text = extractText(html);
                    }

                    int newLinks = 0;
                    if (link.depth() < site.maxDepth()) {
                        for (var candidate : extractLinks(html, seedUri)) {
                            if (visited.add(candidate.toString())) {
                                queue.add(new CrawledLink(candidate, link.depth() + 1));
                                newLinks++;
                            }
                        }
                    }
                    log.debug("Página lida: {} (profundidade {}, {} caracteres, {} links novos)",
                            url, link.depth(), text.length(), newLinks);

                    if (text.isBlank()) {
                        log.warn("Página sem texto; ignorada: {}", url);
                        skipped++;
                    } else if (!seenTexts.add(text)) {
                        log.warn("Página com texto idêntico a outra já lida; ignorada: {}", url);
                        skipped++;
                    } else {
                        onPage.accept(new CrawledPage(url, html.title(), text, link.depth()));
                        ingested++;
                    }
                } catch (UnsupportedMimeTypeException e) {
                    log.warn("Conteúdo não-HTML ({}); ignorado: {}", e.getMimeType(), url);
                    skipped++;
                } catch (IOException | RuntimeException e) {
                    log.error("Falha ao processar a página {}", url, e);
                    failed++;
                }
            }
        }

        if (!queue.isEmpty()) {
            log.warn("Limite de {} páginas atingido em {}; {} links ficaram na fila", site.maxPages(), seedUri, queue.size());
        }
        log.info("Crawl de {} concluído em {} ms: {} páginas lidas, {} ingeridas, {} ignoradas, {} com falha",
                seedUri, elapsedMillis(start), fetched, ingested, skipped, failed);
    }

    /**
     * Usa só os elementos mais externos que casam com {@link #CSS_QUERY}: um {@code <p>} dentro de um
     * {@code <article>} também casa, e somar os dois duplicaria o texto.
     */
    static String extractText(Document html) {
        var selected = html.select(CSS_QUERY);
        var text = selected.stream()
                .filter(element -> element.parents().stream().noneMatch(selected::contains))
                .map(Element::text)
                .collect(Collectors.joining(" "));
        if (text.isBlank() && html.body() != null) {
            text = html.body().text();
        }
        return text;
    }

    /** Links absolutos, normalizados e dentro do escopo da semente. */
    static List<URI> extractLinks(Document html, URI seed) {
        var links = new ArrayList<URI>();
        for (var anchor : html.select("a[href]")) {
            normalize(anchor.absUrl("href"))
                    .filter(uri -> isInScope(uri, seed))
                    .ifPresent(links::add);
        }
        return links;
    }

    /**
     * Remove o fragmento e a barra final, e padroniza esquema e host em minúsculas.
     * Devolve vazio para URLs que não são http(s) (mailto:, tel:, javascript:) ou malformadas.
     */
    static Optional<URI> normalize(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            var uri = new URI(url.strip());
            var scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme) || uri.getHost() == null) {
                return Optional.empty();
            }
            var path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return Optional.of(new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(),
                    path, uri.getQuery(), null));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Mesmo host e caminho igual ou abaixo do caminho da semente; arquivos não-HTML ficam de fora. */
    static boolean isInScope(URI candidate, URI seed) {
        if (!candidate.getHost().equals(seed.getHost())) {
            return false;
        }
        var path = candidate.getPath();
        if (NON_HTML_PATH.matcher(path).matches()) {
            return false;
        }
        var prefix = seed.getPath();
        return "/".equals(prefix) || path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * Parser mínimo de robots.txt: coleta os {@code Disallow} dos grupos de {@code User-agent: *}.
     */
    static List<String> parseDisallowedPaths(String robotsTxt) {
        var disallowed = new ArrayList<String>();
        boolean groupApplies = false;
        boolean readingAgents = false;
        for (var rawLine : robotsTxt.split("\\R")) {
            var line = rawLine.replaceFirst("#.*", "").strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            var field = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            var value = line.substring(colon + 1).strip();
            if ("user-agent".equals(field)) {
                // Linhas User-agent seguidas formam um mesmo grupo.
                groupApplies = (readingAgents && groupApplies) || "*".equals(value);
                readingAgents = true;
            } else {
                readingAgents = false;
                if (groupApplies && "disallow".equals(field) && !value.isEmpty()) {
                    disallowed.add(value);
                }
            }
        }
        return disallowed;
    }

    static boolean isDisallowed(String path, List<String> disallowed) {
        return disallowed.stream().anyMatch(path::startsWith);
    }

    private List<String> fetchDisallowedPaths(URI seed) {
        var robotsUrl = seed.resolve("/robots.txt").toString();
        try {
            var body = Jsoup.connect(robotsUrl).userAgent(USER_AGENT).timeout(HTTP_TIMEOUT_MS)
                    .ignoreContentType(true).execute().body();
            return parseDisallowedPaths(body);
        } catch (IOException e) {
            log.warn("robots.txt indisponível em {} ({}); seguindo sem restrições", robotsUrl, e.getMessage());
            return List.of();
        }
    }

    private static boolean pause(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record CrawledLink(URI uri, int depth) {
    }

    /**
     * Abre o Playwright e o Chromium só na primeira página dinâmica e os reaproveita até o fim do crawl.
     */
    private static final class DynamicRenderer implements AutoCloseable {

        private Playwright playwright;
        private Browser browser;

        String render(String url) {
            log.info("Renderizando página dinâmica com Playwright: {}", url);
            var start = System.nanoTime();
            ensureBrowser();

            try (Page page = browser.newPage(new Browser.NewPageOptions().setUserAgent(USER_AGENT))) {
                page.setDefaultTimeout(DYNAMIC_PAGE_TIMEOUT_MS);

                var response = page.navigate(url);
                if (response == null) {
                    log.warn("Navegação sem resposta HTTP para {}", url);
                } else if (!response.ok()) {
                    log.warn("Página dinâmica {} respondeu HTTP {}", url, response.status());
                } else {
                    log.debug("Página {} carregada (HTTP {}) em {} ms", url, response.status(), elapsedMillis(start));
                }

                // O seletor é específico de um site; nos demais, segue com o que já foi renderizado.
                try {
                    page.waitForSelector(DYNAMIC_CONTENT_SELECTOR);
                    log.debug("Seletor {} encontrado em {}", DYNAMIC_CONTENT_SELECTOR, url);
                } catch (TimeoutError e) {
                    log.warn("Seletor {} não apareceu em {} ms em {}; usando o HTML atual",
                            DYNAMIC_CONTENT_SELECTOR, (long) DYNAMIC_PAGE_TIMEOUT_MS, url);
                }

                // Rola até o fim e espera a rede assentar para carregar conteúdo lazy.
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                } catch (TimeoutError e) {
                    log.warn("Rede não ficou ociosa após o scroll em {}; usando o HTML atual", url);
                }

                String renderedHtml = page.content();
                log.info("Página dinâmica renderizada: {} ({} caracteres de HTML em {} ms)",
                        url, renderedHtml.length(), elapsedMillis(start));
                return renderedHtml;
            } catch (PlaywrightException e) {
                log.error("Falha no Playwright ao renderizar {} após {} ms", url, elapsedMillis(start), e);
                throw e;
            }
        }

        private void ensureBrowser() {
            if (browser != null) {
                return;
            }
            var start = System.nanoTime();
            // Playwright.create() sobe o processo do driver (e baixa o Chromium na primeira execução).
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            log.debug("Chromium iniciado em {} ms", elapsedMillis(start));
        }

        @Override
        public void close() {
            if (playwright != null) {
                // Fechar o Playwright encerra também o navegador.
                playwright.close();
                log.debug("Playwright encerrado");
            }
        }
    }
}
