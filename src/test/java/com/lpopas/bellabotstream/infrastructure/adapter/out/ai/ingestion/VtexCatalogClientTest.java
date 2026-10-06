package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.ingestion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VtexCatalogClientTest {

    private static final String HOST = "loja.marykay.com.br";

    private static List<VtexCatalogClient.Product> products() throws IOException {
        try (var json = VtexCatalogClientTest.class.getResourceAsStream("/vtex/products-search.json")) {
            return VtexCatalogClient.parseProducts(new String(json.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldBuildDocumentWithPriceCategoriesAndCleanDescription() throws IOException {
        var document = VtexCatalogClient.toDocument(products().get(0), HOST).orElseThrow();

        assertThat(document.text())
                .contains("Produto: Batom Cremoso Mary Kay")
                .contains("Marca: Mary Kay")
                .contains("Categorias: Maquiagem > Lábios; Maquiagem")
                .contains("Preço: R$ 49,90 (de R$ 59,90)")
                .contains("Descrição: Batom Cremoso Cor intensa e hidratação.")
                .contains("Link: https://loja.marykay.com.br/batom-cremoso-mary-kay/p")
                .doesNotContain("<strong>");
        assertThat(document.metadata().getString("source")).isEqualTo("https://loja.marykay.com.br/batom-cremoso-mary-kay/p");
        assertThat(document.metadata().getString("type")).isEqualTo(VtexCatalogClient.TYPE_PRODUCT);
        assertThat(document.metadata().getString("store")).isEqualTo(HOST);
        assertThat(document.metadata().getString("price")).isEqualTo("49.9");
    }

    @Test
    void shouldSkipProductWithoutStock() throws IOException {
        assertThat(VtexCatalogClient.toDocument(products().get(1), HOST)).isEqualTo(Optional.empty());
    }

    @Test
    void shouldOmitListPriceAndDescriptionWhenAbsent() throws IOException {
        var document = VtexCatalogClient.toDocument(products().get(2), HOST).orElseThrow();

        assertThat(document.text())
                .contains("Preço: R$ 11,90\n")
                .doesNotContain("(de ")
                .doesNotContain("Descrição:");
    }

    @Test
    void shouldFormatPriceInBrazilianReal() {
        assertThat(VtexCatalogClient.formatPrice(new BigDecimal("1234.5"))).isEqualTo("R$ 1234,50");
    }
}
