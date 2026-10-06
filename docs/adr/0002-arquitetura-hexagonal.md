# 2. Arquitetura hexagonal (ports & adapters)

Data: 2026-10-06

## Status

Aceito

## Contexto

O `bellabot-stream` integra quatro tecnologias externas, cada uma com uma API própria e uma evolução rápida:

- **Telegram:** `telegrambots` 10.x. Na versão 10 a API mudou bastante: a `TelegramLongPollingBot` deixou de existir e passou a haver `SpringLongPollingBot` e `TelegramClient`.
- **LLM local:** LangChain4j 1.x com Ollama, cujo modelo (`medgemma1.5:4b`) e cujos prompts mudam com frequência.
- **Banco vetorial:** ChromaDB, que no Chroma 1.x removeu a API v1, ainda o padrão do LangChain4j.
- **Mensageria:** Kafka com Avro e Schema Registry.

As regras que dão valor ao produto não dependem de nenhuma delas: o que responder, quando avisar o cliente de uma falha, o que publicar e como traduzir uma análise facial em conselho. Também é preciso testar essas regras sem subir Telegram, Kafka, Chroma e Ollama. O `contextLoads`, que sobe o contexto completo, depende de toda a infraestrutura e de um token real, por isso só roda com `BELLABOT_IT=true`.

O `CLAUDE.md` da pasta-pai descreve outro projeto (camadas `adapter/application/domain/shared`, Spring Batch, JPA). Este repositório precisa de uma estrutura própria, explícita, para não herdar aquelas convenções.

## Decisão

Adotar a **arquitetura hexagonal**, com as dependências apontando sempre para dentro: `infrastructure → application → domain`. O pacote base é `com.lpopas.bellabotstream`.

- **`domain/`:** Java puro, sem anotações nem imports de Spring, Telegram, Kafka ou LangChain4j.
  - Modelos: `IncomingMessage`, `PictureMessage`.
  - Value objects: `Photo`.
  - Eventos de domínio em records: `MessageProcessedEvent`, que é distinto do record Avro.
  - Serviços de domínio: `ConsultationGuide`.
- **`application/`:**
  - `port/in`: `ReplyToMessageUseCase`.
  - `port/out`: `GenerateAiReplyPort`, `GenerateEmbeddingPort`, `SendMessagePort` (com `TypingIndicator`) e `PublishEventPort`.
  - `usecase`: `ReplyToMessageService`, onde `@Service` é permitido, mas nenhum import de Telegram, Kafka ou LangChain4j.
- **`infrastructure/`:**
  - `config/{telegram,kafka,ai}`: beans e `@ConfigurationProperties`.
  - `adapter/in`: `BellaBotTelegramBot` e `OllamaEmbeddingInitializer`.
  - `adapter/out`: `TelegramMessageSender`, `KafkaEventPublisher`, `LangChain4jReplyAdapter`, `OllamaEmbeddingAdapter` e a ingestão.
- **Conversão de tipos externos na borda:**
  - `PhotoSize` vira `Photo` no adapter de entrada;
  - `UserMessage` e `ImageContent` do LangChain4j são montados no `LangChain4jReplyAdapter`;
  - o evento de domínio vira `BellaUserMessage` (Avro) no `KafkaEventPublisher`;
  - o HTML do Telegram é produzido e dividido no adapter de saída.
- **`package-info.java` em cada pacote,** descrevendo sua responsabilidade. É a referência para decidir onde uma classe nova deve ficar.
- **Testes por camada:**
  - domínio e use cases, com JUnit e as ports mockadas, sem Spring;
  - adapters, com o cliente externo mockado (`TelegramClient`) ou com fixtures (`products-search.json`);
  - o contexto completo, só quando `BELLABOT_IT=true`.

## Consequências

**Positivas**

- O núcleo foi testado sem nenhuma infraestrutura. Os 15 testes do `ReplyToMessageServiceTest` e os de `ConsultationGuide` rodam em milissegundos, e o `./mvnw test` passa sem Docker nem Ollama.
- As mudanças de tecnologia ficaram contidas nos adapters. Nesta base, por exemplo:
  - o "digitando..." entrou como uma port nova (`startTyping`), implementada só no adapter do Telegram;
  - trocar o template do RAG, o modo JSON da análise de foto e a divisão de mensagens HTML não tocou o domínio;
  - o schema Avro pôde mudar sem afetar o evento de domínio.
- Quando uma regra de negócio precisou sair do prompt, ela teve lugar natural e testável: o `ConsultationGuide`, no domínio (ver ADR 0001).
- A estrutura deixa claro o que pertence a quem, o que facilita revisões e orienta assistentes de código.

**Negativas**

- **Mais tipos e mapeamentos.** Há duplicação deliberada entre o evento de domínio e o record Avro, e entre `Photo` e `PhotoSize`, além de interfaces com uma única implementação.
- **Ports que vazam detalhes.** Algumas ports ainda refletem o canal (`SendMessagePort.downloadFile`, `TypingIndicator`). Se surgir um segundo canal (WhatsApp, web), elas vão precisar ser revisadas.
- **Fronteira mantida pela disciplina.** Nada impede um import proibido no domínio; a regra é sustentada pela revisão.
- **Pequenas violações de camada já existem:**
  - o `OllamaEmbeddingInitializer` é um adapter de entrada que chama uma port de saída (`GenerateEmbeddingPort`) sem passar por um use case;
  - ~~o texto do pedido à Bella ("Esta é a análise facial…") fica na camada de aplicação, embora exista por uma limitação do modelo.~~ Resolvido em 2026-10-06: a port ganhou `generateConsultation` e `generateInvalidPhotoReply`, e os textos de prompt passaram para o `LangChain4jReplyAdapter`. O tipo MIME da foto, que também era um detalhe do Telegram no caso de uso, agora vem do adapter do Telegram num `FileContent`.

**Próximos passos sugeridos**

- Adicionar ArchUnit para validar as regras de dependência no build: domínio sem Spring, aplicação sem Telegram, Kafka ou LangChain4j.
- Introduzir um use case de ingestão (`port/in`) para o `OllamaEmbeddingInitializer`, alinhando a carga do RAG ao padrão.
