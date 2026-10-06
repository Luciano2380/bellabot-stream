package com.lpopas.bellabotstream.infrastructure.config.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Fontes da carga inicial do RAG (ingestão no ChromaDB feita na subida da aplicação).
 */
@ConfigurationProperties(prefix = "bellabot.ai.rag")
public record RagProperties(
        Pdf pdf,
        Site site,
        Catalog catalog
) {

    public RagProperties {
        pdf = pdf == null ? new Pdf(null) : pdf;
        site = site == null ? new Site(null, null, null, null) : site;
        catalog = catalog == null ? new Catalog(null) : catalog;
    }

    /** Caminho no formato do {@code ResourceLoader} (ex.: {@code classpath:pdf/arquivo.pdf}). */
    public record Pdf(String path) {
    }

    /**
     * Sites percorridos com seus sublinks (mesmo host e caminho abaixo da URL informada).
     *
     * @param maxDepth     quantos níveis de links seguir a partir da URL informada (0 = só a própria página)
     * @param maxPages     máximo de páginas lidas por URL informada
     * @param requestDelay intervalo entre requisições ao mesmo site
     */
    public record Site(List<String> urls, Integer maxDepth, Integer maxPages, Duration requestDelay) {

        public Site {
            urls = urls == null ? List.of() : List.copyOf(urls);
            maxDepth = maxDepth == null ? 2 : maxDepth;
            maxPages = maxPages == null ? 50 : maxPages;
            requestDelay = requestDelay == null ? Duration.ofSeconds(1) : requestDelay;
        }
    }

    /** Lojas VTEX cujo catálogo público é ingerido (um documento por produto). */
    public record Catalog(List<String> storeUrls) {

        public Catalog {
            storeUrls = storeUrls == null ? List.of() : List.copyOf(storeUrls);
        }
    }
}
