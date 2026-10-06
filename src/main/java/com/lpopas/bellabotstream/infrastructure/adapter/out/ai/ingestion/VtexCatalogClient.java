package com.lpopas.bellabotstream.infrastructure.adapter.out.ai.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Lê o catálogo público de uma loja VTEX ({@code /api/catalog_system/pub/products/search})
 * e monta um {@link Document} por produto com nome, preço, categorias, descrição e link.
 */
@Slf4j
@Component
public class VtexCatalogClient {

    public static final String TYPE_PRODUCT = "product";

    static final String SEARCH_PATH = "/api/catalog_system/pub/products/search";
    /** Máximo de itens por página aceito pela API. */
    static final int PAGE_SIZE = 50;
    /** A API não pagina além do item 2500. */
    static final int MAX_ITEMS = 2_500;

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    // A aplicação não é web: não há bean ObjectMapper no contexto.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Busca todo o catálogo da loja. Produtos sem estoque ficam de fora para a Bella não recomendá-los.
     *
     * @throws IOException se alguma página do catálogo não puder ser lida (nada é devolvido pela metade)
     */
    public List<Document> fetchProducts(String storeUrl) throws IOException {
        var start = System.nanoTime();
        var store = URI.create(storeUrl.strip());
        var host = store.getHost();
        var documents = new ArrayList<Document>();
        int read = 0;

        for (int from = 0; from < MAX_ITEMS; from += PAGE_SIZE) {
            var products = parseProducts(fetchPage(store, from, from + PAGE_SIZE - 1));
            read += products.size();
            products.stream()
                    .map(product -> toDocument(product, host))
                    .flatMap(Optional::stream)
                    .forEach(documents::add);
            log.debug("Catálogo {}: página a partir de {} com {} produtos", host, from, products.size());
            if (products.size() < PAGE_SIZE) {
                break;
            }
        }

        log.info("Catálogo {} lido em {} ms: {} produtos, {} com estoque, {} ignorados (sem estoque)",
                host, (System.nanoTime() - start) / 1_000_000, read, documents.size(), read - documents.size());
        return documents;
    }

    private String fetchPage(URI store, int from, int to) throws IOException {
        var uri = store.resolve(SEARCH_PATH + "?_from=" + from + "&_to=" + to);
        var request = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", WebCrawler.USER_AGENT)
                .GET()
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // A API responde 206 (Partial Content) quando há mais páginas.
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                throw new IOException("Catálogo respondeu HTTP " + response.statusCode() + " em " + uri);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Leitura do catálogo interrompida em " + uri, e);
        }
    }

    static List<Product> parseProducts(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, new TypeReference<>() {
        });
    }

    /** Vazio quando o produto não tem nenhuma oferta com estoque. */
    static Optional<Document> toDocument(Product product, String storeHost) {
        var offer = firstAvailableOffer(product);
        if (offer.isEmpty()) {
            log.debug("Produto sem estoque ignorado: {}", product.productName());
            return Optional.empty();
        }
        var price = offer.get().price();
        var listPrice = offer.get().listPrice();

        var text = new StringBuilder()
                .append("Produto: ").append(product.productName()).append('\n');
        if (product.brand() != null && !product.brand().isBlank()) {
            text.append("Marca: ").append(product.brand()).append('\n');
        }
        var categories = formatCategories(product.categories());
        if (!categories.isEmpty()) {
            text.append("Categorias: ").append(categories).append('\n');
        }
        text.append("Preço: ").append(formatPrice(price));
        if (listPrice != null && listPrice.compareTo(price) > 0) {
            text.append(" (de ").append(formatPrice(listPrice)).append(')');
        }
        text.append('\n');
        if (product.description() != null && !product.description().isBlank()) {
            text.append("Descrição: ").append(Jsoup.parse(product.description()).text()).append('\n');
        }
        text.append("Link: ").append(product.link());

        var metadata = new Metadata()
                .put("source", product.link())
                .put("type", TYPE_PRODUCT)
                .put("store", storeHost)
                .put("name", product.productName())
                .put("price", price.toPlainString());
        return Optional.of(Document.from(text.toString(), metadata));
    }

    private static Optional<Offer> firstAvailableOffer(Product product) {
        if (product.items() == null) {
            return Optional.empty();
        }
        return product.items().stream()
                .filter(item -> item.sellers() != null)
                .flatMap(item -> item.sellers().stream())
                .map(Seller::offer)
                .filter(Objects::nonNull)
                .filter(offer -> offer.availableQuantity() > 0 && offer.price() != null)
                .findFirst();
    }

    /** "/Presentes/Acessórios/" vira "Presentes > Acessórios"; categorias repetidas aparecem uma vez. */
    static String formatCategories(List<String> categories) {
        if (categories == null) {
            return "";
        }
        return categories.stream()
                .map(path -> path.replaceAll("^/|/$", "").replace("/", " > "))
                .filter(category -> !category.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .collect(Collectors.joining("; "));
    }

    static String formatPrice(BigDecimal value) {
        return String.format(PT_BR, "R$ %.2f", value);
    }

    /** Somente os campos usados da resposta da API. */
    record Product(
            String productName,
            String brand,
            String link,
            List<String> categories,
            String description,
            List<Item> items
    ) {
    }

    record Item(List<Seller> sellers) {
    }

    record Seller(@JsonProperty("commertialOffer") Offer offer) {
    }

    record Offer(
            @JsonProperty("Price") BigDecimal price,
            @JsonProperty("ListPrice") BigDecimal listPrice,
            @JsonProperty("AvailableQuantity") int availableQuantity
    ) {
    }
}
