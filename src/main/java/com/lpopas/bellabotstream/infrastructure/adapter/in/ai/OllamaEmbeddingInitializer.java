package com.lpopas.bellabotstream.infrastructure.adapter.in.ai;

import com.lpopas.bellabotstream.application.port.out.GenerateEmbeddingPort;
import com.lpopas.bellabotstream.infrastructure.config.ai.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaEmbeddingInitializer implements CommandLineRunner {

    private final GenerateEmbeddingPort generateEmbeddingPort;
    private final RagProperties ragProperties;

    @Override
    public void run(String... args) {
        log.info("=== Iniciando Carga Automática de Dados RAG ===");
        var start = System.nanoTime();

        // Cada etapa isolada: uma falha no catálogo não impede a ingestão dos sites nem do PDF.
        runStep("catálogo de produtos", () -> generateEmbeddingPort.ingestProductCatalog(ragProperties.catalog().storeUrls()));
        runStep("sites", () -> generateEmbeddingPort.ingestWebsites(ragProperties.site().urls()));
        runStep("PDF", () -> generateEmbeddingPort.ingestLocalPdf(ragProperties.pdf().path()));

        log.info("=== Carga Automática Concluída em {} ms ===", elapsedMillis(start));
    }

    private void runStep(String name, Runnable step) {
        var start = System.nanoTime();
        log.info("Carga RAG: iniciando {}", name);
        try {
            step.run();
            log.info("Carga RAG: {} concluído em {} ms", name, elapsedMillis(start));
        } catch (RuntimeException e) {
            log.error("Carga RAG: falha em {} após {} ms", name, elapsedMillis(start), e);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
