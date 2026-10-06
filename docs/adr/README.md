# Architecture Decision Records

Decisões de arquitetura do `bellabot-stream`, no formato de Michael Nygard (Título, Status, Contexto, Decisão, Consequências).

| Nº | Decisão | Status |
|---|---|---|
| [0001](0001-pipeline-de-ia-local-com-dois-agentes-e-regras-no-dominio.md) | Pipeline de IA local com dois agentes e regras de consultoria no domínio | Aceito |
| [0002](0002-arquitetura-hexagonal.md) | Arquitetura hexagonal (ports & adapters) | Aceito |
| [0003](0003-kafka-avro-eventos-de-sucesso.md) | Kafka com Avro e publicação apenas de mensagens processadas com sucesso | Aceito |
| [0004](0004-carga-do-rag-na-subida-com-ingestao-idempotente.md) | Carga do RAG na subida da aplicação, com ingestão idempotente | Aceito |

Para registrar uma nova decisão, crie o próximo número (`NNNN-titulo-em-kebab-case.md`). Quando ela substituir outra, marque a anterior como "Substituído por NNNN".
