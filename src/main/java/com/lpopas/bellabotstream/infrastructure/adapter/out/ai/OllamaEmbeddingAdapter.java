package com.lpopas.bellabotstream.infrastructure.adapter.out.ai;

import com.lpopas.bellabotstream.application.port.out.GenerateEmbeddingPort;
import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.ingestion.CrawledPage;
import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.ingestion.VtexCatalogClient;
import com.lpopas.bellabotstream.infrastructure.adapter.out.ai.ingestion.WebCrawler;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaEmbeddingAdapter implements GenerateEmbeddingPort {

    static final int EMBEDDING_BATCH_SIZE = 32;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ResourceLoader resourceLoader;
    private final WebCrawler webCrawler;
    private final VtexCatalogClient vtexCatalogClient;

    /**
     * Percorre cada site com seus sublinks. Antes de gravar uma página, apaga os segmentos antigos
     * com o mesmo {@code source}, para que uma nova ingestão substitua o conteúdo em vez de duplicá-lo.
     */
    @Override
    public void ingestWebsites(List<String> urls) {
        for (var url : urls) {
            webCrawler.crawl(url, this::storePage);
        }
    }

    private void storePage(CrawledPage page) {
        var metadata = Metadata.from("source", page.url());
        if (page.title() != null && !page.title().isBlank()) {
            metadata.put("title", page.title());
        }
        embeddingStore.removeAll(metadataKey("source").isEqualTo(page.url()));
        processAndStore(List.of(Document.from(page.text(), metadata)));
        log.info("Página ingerida: {} ({} caracteres)", page.url(), page.text().length());
    }

    /**
     * Substitui todos os produtos da loja no Chroma. O catálogo inteiro é lido antes de apagar,
     * para que uma falha na leitura não deixe o RAG sem produtos; produtos que saíram da loja também somem.
     */
    @Override
    public void ingestProductCatalog(List<String> storeUrls) {
        for (var storeUrl : storeUrls) {
            try {
                var documents = vtexCatalogClient.fetchProducts(storeUrl);
                if (documents.isEmpty()) {
                    log.warn("Catálogo de {} sem produtos com estoque; produtos já gravados foram mantidos", storeUrl);
                    continue;
                }
                var host = URI.create(storeUrl.strip()).getHost();
                embeddingStore.removeAll(metadataKey("type").isEqualTo(VtexCatalogClient.TYPE_PRODUCT)
                        .and(metadataKey("store").isEqualTo(host)));
                processAndStore(documents);
                log.info("Catálogo de {} ingerido: {} produtos", host, documents.size());
            } catch (IOException e) {
                log.error("Erro ao ler o catálogo de {}: {}", storeUrl, e.getMessage());
            } catch (RuntimeException e) {
                // Falha em uma loja (ex.: embedding/Chroma) não deve impedir a ingestão das demais.
                log.error("Falha ao ingerir o catálogo de {}", storeUrl, e);
            }
        }
    }

    /**
     * Antes de gravar, apaga os segmentos com o mesmo {@code source} (o caminho configurado),
     * para que cada subida substitua o PDF em vez de duplicá-lo.
     */
    @Override
    public void ingestLocalPdf(String pathPdf) {
        var resource = resourceLoader.getResource(pathPdf);
        if (!resource.exists()) {
            log.error("Arquivo PDF não foi localizado em: {}", pathPdf);
            return;
        }

        try (InputStream inputStream = resource.getInputStream()) {
            log.info("Processando extração do PDF local...");
            var parser = new ApacheTikaDocumentParser();
            var document = parser.parse(inputStream);
            document.metadata().put("source", pathPdf);

            embeddingStore.removeAll(metadataKey("source").isEqualTo(pathPdf));
            processAndStore(List.of(document));
            log.info("Ingestão do arquivo PDF concluída com sucesso.");
        } catch (IOException e) {
            log.error("Erro crítico na leitura/parsing do arquivo PDF: {}", e.getMessage());
        }
    }

    private void processAndStore(List<Document> documents) {
        var start = System.nanoTime();
        var splitter = DocumentSplitters.recursive(600, 60);
        List<TextSegment> segments = new ArrayList<>();
        documents.forEach(document -> segments.addAll(splitter.split(document)));
        log.info("Gerando embeddings de {} segmentos de {} documento(s) (lotes de {})",
                segments.size(), documents.size(), EMBEDDING_BATCH_SIZE);

        // Em lotes: uma chamada ao Ollama e uma ao Chroma por lote, em vez de uma por segmento.
        for (int from = 0; from < segments.size(); from += EMBEDDING_BATCH_SIZE) {
            var batch = segments.subList(from, Math.min(from + EMBEDDING_BATCH_SIZE, segments.size()));
            var embeddings = embeddingModel.embedAll(batch).content();
            embeddingStore.addAll(embeddings, batch);
            log.debug("Embeddings armazenados: {}/{} ({} ms)",
                    from + batch.size(), segments.size(), elapsedMillis(start));
        }
        log.info("{} segmentos armazenados no Chroma em {} ms", segments.size(), elapsedMillis(start));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
