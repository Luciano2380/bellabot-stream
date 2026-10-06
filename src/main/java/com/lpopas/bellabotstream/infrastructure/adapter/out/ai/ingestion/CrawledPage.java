package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.ingestion;

/**
 * Página lida pelo crawler. {@code url} já vem normalizada e é usada como metadado {@code source}.
 */
public record CrawledPage(String url, String title, String text, int depth) {
}
