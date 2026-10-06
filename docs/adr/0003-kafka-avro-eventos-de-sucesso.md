# 3. Kafka com Avro e publicação apenas de mensagens processadas com sucesso

Data: 2026-10-06

## Status

Aceito

## Contexto

As consultorias geradas pelo bot têm valor fora do Telegram: análise do atendimento, acompanhamento pela consultora, histórico do cliente e futuras integrações. Esses consumidores ainda não existem, e cada um pode evoluir no próprio ritmo. Por isso a integração precisa ser assíncrona e desacoplada do fluxo de resposta.

As restrições e fatos relevantes:

- **O bot é a fonte do evento, mas não pode depender dele.** O cliente já recebeu a resposta quando o evento é publicado, e uma falha no broker não deve virar erro para o cliente.
- **Os consumidores precisam de um contrato estável** que evolua sem quebrá-los.
- **A ordem por conversa importa** para quem reconstrói o histórico de um cliente.
- **O conteúdo gerado é ruidoso.** A resposta da Bella sai em HTML do Telegram, às vezes com Markdown residual, e o raciocínio do modelo é descartado. Também há falhas da IA: timeout ou resposta vazia por causa do `num-predict`.
- **Decisão de produto:** só o fluxo de foto deve gerar evento. O fluxo de texto e o `/start` não publicam, por decisão do responsável pelo produto.
- **Ainda não há consumidor no projeto.** O `adapter/in/kafka` está vazio.

## Decisão

1. **Kafka como barramento, com Avro e Confluent Schema Registry.**
   - O schema fica em `src/main/avro/bella-user-message.avsc`. O `avro-maven-plugin` gera `BellaUserMessage` (com `stringType=String`), e as classes geradas nunca são editadas.
   - O subject segue a `TopicNameStrategy` (`<tópico>-value`), com compatibilidade **`BACKWARD`**: só são permitidas mudanças compatíveis, como campos novos com default.
   - O schema atual tem `eventId`, `username`, `phone`, `reply`, `status` e `receivedAt`.

2. **Nome do tópico no padrão `<domínio>.<entidade>.<evento>.v<versão>`:** `bella.user-message.processed.v1`. Uma mudança incompatível de schema vai para um novo tópico (`.v2`), em vez de quebrar o contrato do `.v1`.
   - O nome é configurável (`BELLABOT_TOPIC_USER_MESSAGE`), e o tópico é declarado como `NewTopic` em `infrastructure/config/kafka`.
   - Partições e réplicas também são configuráveis (padrão: 3 e 1).

3. **A chave da mensagem é o `chatId`,** o que preserva a ordem dos eventos de um mesmo cliente dentro da partição.

4. **Producer confiável:** `acks=all`, idempotência habilitada, compressão lz4 e `delivery.timeout.ms` de 120s.
   - O consumer fica configurado para quando existir: `ErrorHandlingDeserializer` delegando ao `KafkaAvroDeserializer`, `specific.avro.reader=true`, `read_committed` e sem auto-commit.

5. **Só se publica o que foi processado com sucesso:** a IA respondeu, a resposta não está vazia e o envio ao Telegram não falhou. O `status` é sempre `ANSWERED`. Falhas da IA não geram evento.

6. **A publicação nunca afeta o cliente.** Ela acontece depois do envio, via `publish()` do `ReplyToMessageService`, que captura e registra exceções. O envio do `KafkaTemplate` é assíncrono, e a falha é registrada no `whenComplete`.

7. **O `reply` vai como texto puro.** O `KafkaEventPublisher.toPlainText` remove HTML e Markdown, decodifica entidades e mantém as URLs no formato `texto (url)`. A conversão para o formato do canal fica na borda do Telegram, e o evento carrega um texto neutro.

## Consequências

**Positivas**

- **Consumidores desacoplados.** Novos sistemas podem ler o tópico sem mudança no bot e reprocessar o histórico (`auto-offset-reset: earliest`).
- **Contrato controlado.** O registry rejeita mudanças incompatíveis, e a versão no nome do tópico permite migrar sem quebrar quem já consome.
- **Ordem garantida por cliente** (chave = `chatId`).
- **A falha do broker não degrada o atendimento,** e não há eventos de falhas da IA poluindo o tópico.
- **Texto neutro, sem acoplamento ao canal:** os consumidores não precisam conhecer o HTML do Telegram.

**Negativas**

- **Sem garantia de entrega fim a fim.** Não há outbox nem retry após uma falha de publicação: se o broker estiver fora quando a resposta for enviada, o evento se perde e fica só um log de erro.
- **Visão parcial do atendimento.** Como o fluxo de texto não publica e as falhas também não, o tópico não serve para medir a taxa de falha nem o volume total de conversas. Para isso seria preciso outro evento (por exemplo, `bella.user-message.failed.v1`) ou métricas.
- **Campos obrigatórios sem valor em muitos casos.** O `phone` só existe quando o cliente compartilha o contato, então o adapter envia `""`. Um campo obrigatório que costuma vir vazio é um sinal de que deveria ser opcional, mas mudar isso depois exige uma evolução compatível.
- **O schema perdeu campos úteis em relação à primeira versão:** `chatId`, `userId`, o texto do usuário, a data de processamento e o embedding. Hoje o `chatId` só aparece como chave da mensagem.
- **Infraestrutura sem consumidor.** O Kafka e o Schema Registry (via `docker compose`) são pré-requisitos de execução e ainda não têm uso dentro do projeto.

**Riscos e pontos em aberto**

- **Mudanças de schema daqui para frente.** O subject `bella.user-message.processed.v1-value` passa a existir no primeiro envio. A partir daí, campos novos precisam de default, e campos obrigatórios novos são rejeitados pelo registry.
- **Tópico órfão.** O antigo `bella-user-message` pode existir no broker, porque o `KAFKA_AUTO_CREATE_TOPICS_ENABLE` está habilitado, e deve ser removido manualmente.
- **Outbox.** Se o evento passar a ser crítico (cobrança, CRM), adotar o padrão outbox, o que exigiria persistência local que hoje o projeto não tem.
