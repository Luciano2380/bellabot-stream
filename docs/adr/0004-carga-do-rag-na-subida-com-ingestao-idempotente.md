# 4. Carga do RAG na subida da aplicação, com ingestão idempotente

Data: 2026-10-06

## Status

Aceito

## Contexto

A Bella só pode recomendar produtos e dar orientações com base em conteúdo real (ver ADR 0001). Esse conteúdo vem de três fontes heterogêneas:

- **Catálogo Mary Kay:** uma loja VTEX com API pública (`/api/catalog_system/pub/products/search`), paginada de 50 em 50 e limitada a 2.500 itens. Preço e estoque mudam com frequência.
- **Artigos sobre cuidados com a pele:** sites como `sbd.org.br/cuidados/`, alguns renderizados só com JavaScript.
- **PDF de campanha:** `data/rag/outubro2026.pdf`, com cerca de 32 MB.

As restrições:

- **Projeto local, sem processo separado de ingestão.** Não há agendador, pipeline de dados nem banco relacional, só o ChromaDB (API v2) e o Ollama (`nomic-embed-text`) na mesma máquina.
- **O conteúdo precisa ser atualizado sem intervenção manual,** e a coleção não pode crescer sem limite. No começo, cada subida duplicava o PDF no Chroma: em consultas reais, o mesmo trecho voltou duas vezes entre os 5 resultados.
- **Uma fonte instável não pode impedir a carga das outras,** nem a subida do bot.
- **Os sites de terceiros precisam ser tratados com respeito:** robots.txt e taxa de requisições.

## Decisão

Fazer a **ingestão completa a cada subida**, no `OllamaEmbeddingInitializer` (um `CommandLineRunner`), chamando a `GenerateEmbeddingPort`. Ela roda em **três etapas isoladas**, de modo que a falha em uma é registrada e não impede as outras.

1. **Catálogo (`VtexCatalogClient`):**
   - Gera um documento por produto **com estoque**, contendo nome, marca, categorias, preço, descrição e link.
   - Os metadados são `type=product` e `store=<host>`.
   - O catálogo inteiro é lido **antes** de apagar os produtos antigos da loja. Assim, uma falha na leitura não deixa o RAG sem produtos, e produtos que saíram da loja também somem.

2. **Sites (`WebCrawler`):**
   - Faz uma busca em largura a partir de cada URL de `bellabot.ai.rag.site.urls`, limitada ao mesmo host e ao caminho da URL inicial.
   - Respeita o `robots.txt` (`User-agent: *`), `max-depth`, `max-pages` e o `request-delay`.
   - Páginas sem texto no HTML estático são renderizadas com Playwright, usando um único Chromium por crawl.
   - Cada página substitui os segmentos com o mesmo `source`.

3. **PDF:**
   - Extração com Apache Tika.
   - O arquivo fica **fora do classpath**, em `data/rag/` (`BELLABOT_RAG_PDF_PATH`), e substitui os segmentos com o mesmo `source`.

4. **Idempotência por metadado:** antes de gravar, cada fonte remove o que gravou na subida anterior (`removeAll(Filter)` por `source` ou por `type`+`store`).

5. **Processamento uniforme:** os documentos são divididos com `DocumentSplitters.recursive(600, 60)`. Os embeddings são gerados em lotes de 32 (uma chamada ao Ollama e uma ao Chroma por lote).

6. **Consulta:**
   - O `ContentRetriever` usa `bellabot.ai.retrieval.max-results` (5) e `min-score` (0.83; era 0.6 até 2026-10-06, quando se mediu que saudações recebiam trechos irrelevantes com score de 0,75–0,82).
   - Os trechos chegam à Bella num bloco `[MATERIAL DE CONSULTA]`.
   - O `ChromaEmbeddingStore` é declarado como bean próprio, porque o `langchain4j-chroma` não tem starter e o padrão de API do LangChain4j (v1) não funciona com o Chroma 1.x.

## Consequências

**Positivas**

- **Zero operação.** Basta subir a aplicação para ter o conteúdo atualizado, sem agendador nem processo extra.
- **A coleção não duplica:** cada fonte substitui o próprio conteúdo, e produtos sem estoque ou removidos da loja deixam de ser recomendados.
- **Falhas isoladas:** um site fora do ar ou a VTEX lenta não impedem as demais fontes.
- **Crawler educado e configurável:** sites, profundidade, limite de páginas e intervalo são ajustáveis por variável de ambiente.
- **O PDF fora do jar** reduz o artefato em cerca de 32 MB e pode ser trocado sem build.

**Negativas**

- **O bot atende antes de o RAG estar pronto.** O long polling registra o bot (`Bot @… registrado`) antes do `CommandLineRunner`, e a carga leva **cerca de 2 minutos** (medido em 2026-10-06: catálogo 48s com 151 produtos, sites 64s, PDF 14s). Nesse intervalo:
  - as respostas podem vir sem material, ou com material parcial entre o "apagar" e o "gravar" de uma fonte;
  - a carga disputa o Ollama, ou seja, a GPU, com os pedidos dos usuários.
- **Custo repetido a cada reinício.** Todo o conteúdo é baixado, convertido em embeddings e regravado, mesmo sem mudanças, o que desgasta os sites de terceiros e a API da VTEX.
- **Dados desatualizados entre reinícios.** Preço e estoque só mudam quando a aplicação reinicia. Uma aplicação que roda por dias recomenda o catálogo do dia em que subiu.
- **Janela sem dados por fonte.** A remoção e a gravação não são atômicas: durante a gravação de uma fonte, consultas podem não achar o conteúdo dela.
- **Segmentos antigos sem `source`.** O que foi gravado antes da idempotência do PDF continua na coleção, porque o filtro não alcança esses segmentos, e precisa de uma limpeza manual única (apagar a coleção `bellabot`).
- **Escala limitada.** Com uma única instância não há problema, mas várias instâncias fariam ingestões simultâneas e concorrentes sobre a mesma coleção.

**Riscos e pontos em aberto**

- **Separar a carga do atendimento:** rodar a ingestão em segundo plano e com prioridade menor, ou atrasar o registro do bot até a carga terminar, ou mover a ingestão para um job agendado ou um endpoint de administração.
- **Ingestão incremental:** guardar um hash por `source` (ou a data de modificação do produto na VTEX) e regravar só o que mudou.
- **Atualizar o catálogo em produção sem reinício:** agendar só a etapa do catálogo, por exemplo a cada poucas horas.
- **Precisão da consulta:** a relevância para produtos depende de o embedding da pergunta ou da análise se parecer com a descrição do produto. Filtros por metadado, como `type=product` para a seção de produtos, podem trazer resultados mais precisos que a busca única atual.
