# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Atenção:** o `~/CLAUDE.md` (pai) descreve outro projeto (pacote `com.projeto`, Spring Batch, PostgreSQL/Flyway, Redis, layout `adapter/application/domain/shared`). **Nada disso vale aqui.** Este repositório não tem banco relacional, cache nem batch; siga este arquivo e os ADRs em `docs/adr/`.

## Visão geral
`bellabot-stream` é um bot do Telegram que atua como consultora de maquiagem e cuidados com a pele (Mary Kay). Ele usa IA local (Ollama), consulta um RAG no ChromaDB e publica eventos no Kafka.
- Spring Boot 3.5.x, Java 21. As virtual threads estão habilitadas (`spring.threads.virtual.enabled`).
- Telegram: `telegrambots` **10.x** (`telegrambots-springboot-longpolling-starter` + `telegrambots-client`). A antiga `TelegramLongPollingBot` não existe mais. O bot implementa `SpringLongPollingBot` + `LongPollingUpdateConsumer`, e cada update roda numa virtual thread. O envio usa um `TelegramClient` (`OkHttpTelegramClient`).
- Kafka com **Avro + Confluent Schema Registry**. Por enquanto só há producer: o `adapter/in/kafka` está vazio.
- IA: **LangChain4j 1.x** (não Spring AI), com Ollama (`medgemma1.5:4b` para chat e visão, `nomic-embed-text` para embeddings) e ChromaDB para o RAG.
- Lombok: o annotation processor está configurado nas execuções do `maven-compiler-plugin`.

**Decisões de arquitetura** ficam em `docs/adr/`, no formato de Michael Nygard e numeradas (índice em `docs/adr/README.md`):
- 0001: pipeline de IA com dois agentes;
- 0002: arquitetura hexagonal;
- 0003: Kafka/Avro;
- 0004: carga do RAG.

Registre ali toda decisão estrutural nova. Se ela substituir outra, atualize o Status da anterior. O `README.md` traz a visão geral, a tabela de variáveis de ambiente e a análise de riscos.

**Diagramas** ficam em `docs/architecture/` (referenciados na seção "Diagramas" do `README.md`):
- `arquitetura.drawio`: camadas, ports, adapters, beans de config, recursos e infraestrutura externa;
- `sequencia-fluxo-mensagens.{mmd,drawio}`: `/start`, texto livre e foto (válida, inválida, falha e publicação no Kafka);
- `sequencia-carga-rag.{mmd,drawio}`: as 3 etapas do `OllamaEmbeddingInitializer` e os lotes de embeddings;
- `*.jpg`: imagens exportadas e embutidas no `README.md`. Existe uma para cada diagrama.

Ao mudar a estrutura (classe nova, port, adapter, etapa de fluxo, tópico, serviço externo), atualize o diagrama correspondente:
- **sequências:** o `.mmd` é a fonte. Altere o `.mmd` e reflita a mudança no `.drawio`, editando-o à mão ou reimportando o Mermaid no draw.io (*Organizar → Inserir → Avançado → Mermaid*);
- **imagens:** reexporte o `.jpg` pelo draw.io.

## Comandos
Use o wrapper `./mvnw`: o Maven do sistema é a versão 3.6.3.

```bash
./mvnw clean compile                 # também gera as classes Avro (generate-sources)
./mvnw test
./mvnw test -Dtest=NomeDaClasseTest
./mvnw test -Dtest=NomeDaClasseTest#metodo
./mvnw spring-boot:run               # rode a partir da raiz (o PDF do RAG e os logs usam caminhos relativos)

docker compose up -d                 # kafka:9092 (KRaft), schema-registry:8081, chromadb:8000
```

O Ollama **não** está no docker-compose. Ele roda à parte (`ollama serve`, porta 11434), e os modelos precisam ser baixados com `ollama pull medgemma1.5:4b` e `ollama pull nomic-embed-text`.

- **Variáveis obrigatórias, sem default:** `TELEGRAM_BOT_TOKEN` e `TELEGRAM_BOT_USERNAME`. Nunca coloque um token como default no `application.yaml`. As demais têm default (tabela no `README.md`).
- **Testes:**
  - o `BellabotStreamApplicationTests.contextLoads` sobe o contexto completo (infraestrutura, token real e long polling, que disputa os updates com o bot em execução) e só roda com `BELLABOT_IT=true`;
  - prefira testes sem Spring, com as ports ou o `TelegramClient` mockados.
- **Logs:** vão para o console e para `logs/bellabot.log` (`LOG_FILE`; rotação de 10MB, 7 arquivos). Cada linha traz `[updateId,chatId]` (MDC). Para acompanhar erros: `tail -F logs/bellabot.log | grep -E ' (ERROR|WARN) '`.

## Arquitetura (hexagonal / ports & adapters, ADR 0002)
O pacote base é `com.lpopas.bellabotstream`, e as dependências apontam para dentro: `infrastructure → application → domain`.

- **`domain/`:** Java puro, sem anotações nem imports de Spring, Kafka, Telegram ou LangChain4j.
  - `model`: `IncomingMessage` e `PictureMessage`;
  - `valueobject`: `Photo` e `FileContent`;
  - `event`: `MessageProcessedEvent`, um record de domínio que **não** é o record Avro;
  - `service`: `ConsultationGuide`, as regras de consultoria;
  - `constants`: `BellaConstants`, com os textos que o **cliente** vê ou digita (`TELEGRAM_START`, `INITIAL_MESSAGE`, `AI_FAILURE_MESSAGE`), todos `public static final`. Textos enviados à **LLM** não vão para lá: ficam no adapter de IA, ao lado dos prompts que dependem deles.
- **`application/`:**
  - `port/in`: `ReplyToMessageUseCase`;
  - `port/out`:
    - `GenerateAiReplyPort`: `generate` (texto livre), `analyzePhoto(chatId, FileContent)`, `generateConsultation(chatId, análise, orientações)` e `generateInvalidPhotoReply(chatId)`. Como cada pedido é apresentado à LLM é decisão do adapter;
    - `SendMessagePort`: `send`, `downloadFile` (devolve `FileContent`) e `startTyping` (`TypingIndicator`);
    - `GenerateEmbeddingPort` e `PublishEventPort`;
  - `usecase`: `ReplyToMessageService`. O `@Service` é permitido, mas os use cases nunca importam Kafka, Telegram ou LangChain4j.
- **`infrastructure/config/{telegram,kafka,ai}`:** `@Configuration` e `@ConfigurationProperties`.
- **`infrastructure/adapter/in/`:**
  - `telegram`: `BellaBotTelegramBot`;
  - `ai`: `OllamaEmbeddingInitializer`, que faz a carga do RAG;
  - `kafka`: consumers futuros.
- **`infrastructure/adapter/out/`:**
  - `telegram`: `TelegramMessageSender`;
  - `kafka`: `KafkaEventPublisher`;
  - `ai`:
    - `LangChain4jReplyAdapter`, `OllamaEmbeddingAdapter` e `MarkdownToTelegramHtml`;
    - `MedgemmaThinking`: remove o raciocínio `<unused94>…<unused95>`;
    - `assistant/`: as interfaces `@AiService`;
    - `memory/`: `ThinkingFreeChatMemoryStore`, a memória da conversa sem raciocínio;
    - `ingestion/`: `VtexCatalogClient` e `WebCrawler`.

Cada pacote tem um `package-info.java` que descreve sua responsabilidade; respeite-o ao decidir onde colocar uma classe. Tipos externos são convertidos na borda:
- `PhotoSize` vira `Photo` no adapter de entrada;
- `UserMessage` e `ImageContent` do LangChain4j são montados no `LangChain4jReplyAdapter`;
- o evento de domínio vira Avro no `KafkaEventPublisher`.

## IA: LangChain4j + Ollama (ADR 0001)
Os beans ficam em `infrastructure/config/ai/LangChain4jConfig` e leem `bellabot.ai.*`:
- `bellaChatModel`;
- `bellaPictureModel`;
- `bellaEmbeddingModel`;
- `ChromaEmbeddingStore`;
- `contentRetriever`;
- `bellaRetrievalAugmentor`;
- `bellaChatMemoryProvider`.

- **Sem starters.** Não reintroduza o `langchain4j-ollama-spring-boot-starter`: o `AutoConfig` dele não usa `@ConditionalOnMissingBean` e duplicaria o `ChatModel`. O `langchain4j-chroma` também não tem starter, e a `apiVersion` precisa ser `V2`, porque o Chroma 1.x removeu a v1.
- **`@AiService` com `wiringMode = EXPLICIT`:**
  - `BellaBotAssistant` usa `bellaChatModel`, `bellaChatMemoryProvider` (memória por chatId) e `retrievalAugmentor = "bellaRetrievalAugmentor"`;
  - `PictureDescriptionAssistant` usa `bellaPictureModel` e **não tem memória**: com memória, as fotos anteriores seriam reenviadas ao modelo.
- **O medgemma sempre raciocina** em `<unused94>…<unused95>` antes de responder, gastando de 2.000 a 2.800 tokens. `think:false`, instrução no prompt e prefill não desligam o raciocínio. O `MedgemmaThinking.remove` o descarta em dois pontos: na resposta ao cliente (se não sobrar nada, ela vira `AI_FAILURE_MESSAGE`) e na **memória da conversa** (`adapter/out/ai/memory/ThinkingFreeChatMemoryStore`, usado pelo `bellaChatMemoryProvider`). Sem essa limpeza da memória, cada resposta deixava de 5 a 11 mil caracteres de raciocínio no histórico, e após algumas trocas no mesmo chat o contexto (`num-ctx` 8192) estourava: o Ollama truncava a conversa (`truncated = 1` no `journalctl`) e o modelo devolvia restos sem sentido.
- **Limites que não devem ser removidos:**
  - `chat.num-predict` = 3072 (`OLLAMA_NUM_PREDICT`). Com 1024, o raciocínio era cortado e a resposta ficava vazia. Sem limite, o modelo entra em loop;
  - `repeat-penalty` = 1.1;
  - `chat.max-retries` = 0 (`OLLAMA_CHAT_MAX_RETRIES`): repetir uma chamada que estourou o timeout só multiplica a espera. O `max-retries` de nível superior (2) vale para os embeddings.
- **`bellaPictureModel`:** é o mesmo modelo, mas em **modo JSON** (`ResponseFormat.JSON`), o que suprime os blocos ``` e o raciocínio, com `picture.num-predict` = 2048. Na Bella o modo JSON elimina o raciocínio, mas piora muito a resposta; por isso só a análise de foto o usa.
- **`OLLAMA_TIMEOUT`** padrão é 300s.
- **Velocidade (RTX 3050 6GB):**

  | Situação | Tokens/s | Efeito |
  |---|---|---|
  | Na tomada (80W) | ~48 | uma consultoria leva ~1 min |
  | Bateria em `power-saver` (20W) | ~5 | as respostas estouram o timeout |
  | Depois de uma suspensão do notebook | ~16 | o CUDA pode falhar e o Ollama passa a usar a CPU sem avisar |

  Para diagnosticar:
  - `journalctl -u ollama` (o `print_timing` mostra `n_gen` e tokens/s);
  - `curl localhost:11434/api/ps` (`size_vram` igual a 0 indica que o modelo está na CPU);
  - `nvidia-smi --query-gpu=enforced.power.limit --format=csv`.
  
  Para corrigir a falha de CUDA, reinicie o serviço com `sudo systemctl restart ollama`.
- **Uma única GPU atende tudo em série.** A carga do RAG, os usuários e qualquer avaliação manual de prompt entram na mesma fila.

### Prompts e contrato entre os agentes
O template do medgemma **não tem papel de sistema**: o Ollama cola o prompt de sistema antes da **primeira** mensagem do usuário, separado só por `\n`. Por isso:
- o `bella-system.md` termina com a seção "MENSAGEM DO CLIENTE" e uma linha `---`. Sem esse separador, o modelo às vezes resume o próprio prompt em vez de responder;
- as mensagens da foto que vão para a Bella são escritas como pedidos do cliente (`LangChain4jReplyAdapter.consultationRequest` e `INVALID_PHOTO_REQUEST`), não como rótulos técnicos nem como a análise crua. Esses textos são citados no `bella-system.md` e ficam no adapter de IA, junto dos prompts; o caso de uso só chama `generateConsultation(chatId, análise, orientações)` ou `generateInvalidPhotoReply(chatId)`;
- **cada foto é uma consultoria nova:** o `generateConsultation` limpa a memória do chat (`BellaBotAssistant` estende `ChatMemoryAccess`, `evictChatMemory`) antes de gerar. Com o histórico limpo de raciocínio, o modelo passou a copiar a última resposta da conversa (57 tokens, sem raciocínio) em vez de montar a consultoria. A consultoria gerada entra na memória, então as perguntas seguintes a enxergam;
- o texto livre do cliente vai como `Mensagem do cliente: "…"` (`LangChain4jReplyAdapter.CLIENT_MESSAGE`). Sem essa marca, mensagens curtas como "Obrigada!" ou "👍" na primeira mensagem da conversa eram lidas como reação ao prompt, e o modelo respondia "Entendido, estou pronta para atuar como Bella". Os pedidos do fluxo de foto não recebem a marca;
- os trechos do RAG entram num bloco `[MATERIAL DE CONSULTA]` … `[FIM DO MATERIAL]`, pelo `DefaultContentInjector` do `bellaRetrievalAugmentor`. O prompt trata esse bloco como material a não copiar.

O **contrato** entre os agentes:
- **Formato da análise:** o `message` da análise de foto tem exatamente 7 linhas rotuladas: `Tom de pele:`, `Subtom:`, `Tipo de pele:`, `Contraste:`, `Estação provável:`, `Cores observadas:` e `Limitações:`.
- **Validação:** o `LangChain4jReplyAdapter` registra a análise em DEBUG e emite um WARN quando falta algum rótulo (`ANALYSIS_LABELS`).
- **Mudança de rótulo:** altere juntos o `picture-description-system.md`, o `bella-system.md`, o `ANALYSIS_LABELS` e o `ConsultationGuide`.
- **Sem exemplos concretos no prompt de foto** (use placeholders `<...>`): o modelo copiava o exemplo em vez de analisar a foto.
- **Sem códigos HEX na análise** (cores por nome): o modelo copiava os HEX para a resposta ao cliente.

A **tradução da análise em conselho fica no código.** O `domain/service/ConsultationGuide` mapeia:
- subtom → fundo da base;
- estação → paleta;
- contraste → intensidade;
- tipo de pele → cuidados.

O `ReplyToMessageService` calcula as orientações e as passa ao `generateConsultation`; o `LangChain4jReplyAdapter.consultationRequest` as anexa à análise como "Orientações da consultoria", e a Bella só redige o texto. Com o mapeamento numa tabela do prompt, o modelo de 4B errava cores ou base em 20% a 30% das respostas; com o guia no código, acertou 6 de 6. **Não devolva essa lógica ao prompt:** ajuste-a no `ConsultationGuide` e nos testes dele.

A Bella:
- escreve em Markdown (`**negrito**`, listas com `- `), e o `MarkdownToTelegramHtml` converte para HTML do Telegram;
- não pode citar produto, preço ou link que não esteja no material de consulta;
- termina a consultoria com o aviso e o contato da consultora. Esse contato ainda é o placeholder "Fulana de Tal / 99999-8888", fixo no `bella-system.md`.

**Para avaliar uma mudança de prompt,** reproduza a chamada no `/api/chat` do Ollama com:
- o prompt;
- a mensagem montada como o código monta (análise + orientações + bloco de material);
- trechos reais do Chroma.

Rode várias vezes, com temperatura 0.3 e vários perfis (ex.: Primavera, Inverno, Verão), porque a saída varia bastante entre rodadas. Não rode avaliações enquanto o bot atende: elas disputam a GPU.

## Fluxo implementado
`BellaBotTelegramBot.onUpdateReceived` → `ReplyToMessageService.reply`:

**Texto**
1. `/start` (`BellaConstants.TELEGRAM_START`) recebe `BellaConstants.INITIAL_MESSAGE`, sem passar pela IA e sem "digitando...".
2. Os demais textos vão para `GenerateAiReplyPort.generate` → `BellaBotAssistant`.
3. A resposta segue para `SendMessagePort.send` → `TelegramMessageSender`.

**Foto**
1. `IncomingMessage.largestPhoto()` → `SendMessagePort.downloadFile`, que devolve um `FileContent` (bytes + MIME; o adapter do Telegram deduz o tipo pela extensão do arquivo, com `image/jpeg` como padrão).
2. `GenerateAiReplyPort.analyzePhoto` → `PictureDescriptionAssistant`, que devolve JSON com `photo_status` e `message`, convertido em `PictureMessage`.
3. Se `photo_status ≠ CLEAR_SINGLE_FACE`, `generateInvalidPhotoReply`: a Bella pede uma nova foto. Se a foto for válida, o `ConsultationGuide` calcula as orientações e `generateConsultation` envia análise + orientações à Bella.
4. `generate` → `BellaBotAssistant`.
5. A resposta vai ao Telegram.
6. O evento é publicado no Kafka.

**Regras comuns**
- **"Digitando...":** o use case mantém o indicador com `try (var typing = sendMessagePort.startTyping(chatId))` em volta das chamadas à IA e do download. O `TelegramMessageSender` reenvia `SendChatAction(TYPING)` a cada 4s via `executeAsync`, numa única thread agendada, e o indicador fecha antes do envio da resposta. Falhas do indicador só geram log em DEBUG.
- **Falha da IA ou resposta vazia:** o usuário recebe `BellaConstants.AI_FAILURE_MESSAGE`, e nada é publicado.
- **Envio:** o `TelegramMessageSender.split` divide textos maiores que 4096 caracteres em ponto natural (linha em branco, quebra de linha ou espaço), nunca dentro de uma tag ou entidade. Tags abertas, inclusive `<a href>`, são fechadas no fim de uma parte e reabertas no início da seguinte. Se a Bot API recusar o HTML, a parte é reenviada como texto puro.

## Kafka / Avro (ADR 0003)
- **Schema:** fica em `src/main/avro/bella-user-message.avsc`. O `avro-maven-plugin` gera `BellaUserMessage` em `target/generated-sources/avro` (`stringType=String`). Nunca edite a classe gerada: altere o `.avsc` e recompile. Os campos atuais são `eventId`, `username`, `phone` (obrigatório; o adapter envia `""` quando não há telefone), `reply`, `status` e `receivedAt`.
- **Tópico:** `bella.user-message.processed.v1` (`BELLABOT_TOPIC_USER_MESSAGE`), no padrão `<domínio>.<entidade>.<evento>.v<versão>`.
  - O subject é `<tópico>-value` (`TopicNameStrategy`), com compatibilidade **`BACKWARD`**: só mudanças compatíveis, como campos novos com default. Uma mudança incompatível vai para um novo tópico (`.v2`).
  - O tópico é declarado em `bellabot.kafka.topics.*` e registrado como `NewTopic` em `infrastructure/config/kafka`.
- **Chave = chatId,** o que preserva a ordem por conversa.
- **Producer e consumer:**
  - producer com `acks=all`, idempotente e lz4;
  - consumer já configurado para quando existir: `ErrorHandlingDeserializer` delegando ao `KafkaAvroDeserializer`, `specific.avro.reader=true`, `read_committed` e sem auto-commit.
- **Só o fluxo de foto publica,** e só o que foi processado com sucesso (a IA respondeu e a mensagem foi entregue), com `status=ANSWERED`. **O fluxo de texto não publica, por decisão do produto:** não adicione publicação no `replyToText`.
- **A publicação nunca afeta o cliente:** ela passa pelo `publish()` do use case, que captura exceções.
- **O `reply` vai como texto puro:** o `KafkaEventPublisher.toPlainText` remove HTML e Markdown, decodifica entidades e mantém as URLs como `texto (url)`.

## Carga do RAG na subida (ADR 0004)
O `OllamaEmbeddingInitializer` (`CommandLineRunner`) chama `GenerateEmbeddingPort` → `OllamaEmbeddingAdapter`, em três etapas isoladas (a falha em uma não impede as outras):
1. **`ingestProductCatalog`:** o `VtexCatalogClient` lê a API pública da VTEX (`/api/catalog_system/pub/products/search`, 50 por página, até 2.500 itens) das lojas em `bellabot.ai.rag.catalog.store-urls`. Gera um documento por produto com estoque (nome, marca, categorias, preço, descrição e link). O catálogo é lido inteiro **antes** de apagar os segmentos com `type=product` e `store=<host>`.
2. **`ingestWebsites`:** o `WebCrawler` faz uma busca em largura a partir de cada URL de `bellabot.ai.rag.site.urls`, limitada ao mesmo host e ao caminho da URL inicial. Respeita o `robots.txt` (`User-agent: *`), o `max-depth`, o `max-pages` e o `request-delay`. Páginas sem texto no HTML estático são renderizadas com Playwright (um Chromium por crawl). Cada página apaga os segmentos com o mesmo `source` antes de gravar.
3. **`ingestLocalPdf`:** extração com Tika. O PDF fica fora do classpath, em `data/rag/` (`BELLABOT_RAG_PDF_PATH`, default `file:data/rag/outubro2026.pdf`). Os segmentos com o mesmo `source` são apagados antes de gravar.

Detalhes da carga:
- **Processamento:** os documentos são divididos com `DocumentSplitters.recursive(600, 60)`, e os embeddings são gerados em lotes de 32.
- **Consulta:** o `ContentRetriever` usa `bellabot.ai.retrieval.*` (`RetrievalProperties`: 5 resultados, score mínimo 0.83). O `nomic-embed-text` é usado sem os prefixos `search_query:`/`search_document:`, o que comprime os scores: saudações ficam em 0,75–0,82 e perguntas reais em 0,84–0,90. Com 0,6, toda saudação recebia 5 trechos irrelevantes e o modelo raciocinava até estourar o `num-predict`. O `bella-system.md` também tem uma regra para cumprimentos (resposta curta, sem material). Se mudar o modelo de embeddings ou adotar os prefixos, recalibre esse valor medindo os scores de saudações e de perguntas reais.
- **Atenção:** o bot começa a receber mensagens **antes** de a carga terminar. Ela leva cerca de 2 minutos e, nesse intervalo, disputa a GPU com os usuários.

## Problemas conhecidos (em observação)
- **Loop de raciocínio em saudações:** mensagens muito curtas, como "oi", às vezes fazem o modelo reenumerar as regras do prompt até gastar os 3.072 tokens, e o cliente recebe `AI_FAILURE_MESSAGE` depois de 1 a 3 minutos. O `repeat-penalty` (janela de 64 tokens) não pega repetições longas. Uma alternativa em avaliação é responder saudações, agradecimentos e emojis com um texto fixo, sem chamar a LLM, como já acontece com o `/start`.
- **`Limitações:` ausente na análise:** o agente de foto às vezes omite essa linha (2 de 5 fotos em 2026-10-06), o que gera o WARN do `ANALYSIS_LABELS`. Não afeta o `ConsultationGuide`, que não usa esse campo.
- **Placeholder copiado:** o agente de foto já copiou `<motivo>` literalmente na linha `Tipo de pele`.
- **Memória só em RAM:** cada reinício zera as conversas.

## Configuração
Todo o `application.yaml` segue o padrão `${ENV_VAR:default}`. As propriedades próprias da aplicação ficam sob `bellabot.*`:
- `ai.ollama` (`chat`, `picture`, `embedding`);
- `ai.chat-memory`;
- `ai.vector-store.chroma`;
- `ai.retrieval`;
- `ai.rag` (`pdf`, `site`, `catalog`);
- `kafka.topics`.

## Documento legado
- **`HELP.md`:** é o arquivo gerado pelo Initializr. Seus links apontam para a documentação do Boot 4.1.1, mas o parent do projeto é o 3.5.16.
