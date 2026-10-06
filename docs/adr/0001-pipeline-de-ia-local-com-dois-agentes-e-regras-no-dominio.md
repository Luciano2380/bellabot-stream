# 1. Pipeline de IA local com dois agentes e regras de consultoria no domínio

Data: 2026-10-06

## Status

Aceito

## Contexto

O `bellabot-stream` é um bot do Telegram que atua como consultora de maquiagem e cuidados com a pele (produtos Mary Kay). O fluxo principal começa com uma **foto do rosto** do cliente e termina numa consultoria personalizada: características da pele, cuidados, maquiagem, produtos do catálogo e contato da consultora. As mensagens processadas com sucesso são publicadas no Kafka (`bella.user-message.processed.v1`, Avro).

As forças em jogo:

- **Privacidade e custo.** As fotos de rosto são dados pessoais sensíveis. Rodar a IA localmente, com Ollama numa única máquina, evita enviar imagens a terceiros e dispensa custo por chamada. Em troca, o projeto fica preso ao hardware disponível: uma RTX 3050 de 6 GB, que comporta um modelo multimodal de cerca de 4B parâmetros (`medgemma1.5:4b`).
- **Comportamento medido do modelo.** Os testes com o `medgemma1.5:4b` mostraram:
  - ele **sempre raciocina** (`<unused94>…<unused95>`) antes de responder, consumindo de 2.000 a 2.800 tokens por resposta. O raciocínio não pode ser desligado por `think:false`, por instrução no prompt nem por prefill;
  - o **modo JSON** do Ollama suprime o raciocínio, mas, na redação livre da Bella, a resposta piorou muito: o modelo ignorou a análise e pediu os dados de novo;
  - o template do modelo **não tem papel de sistema**: o prompt de sistema é colado antes da primeira mensagem do usuário, separado por `\n`. Sem um separador explícito, o modelo às vezes resumia o próprio prompt em vez de responder;
  - ele **copia exemplos concretos** do prompt: uma análise de foto real saiu quase idêntica ao exemplo;
  - ele **não consulta tabelas de forma confiável**: com o mapeamento "subtom → base" e "estação → cores" no prompt, de 20% a 30% das respostas trouxeram a base ou a paleta errada;
  - ele **copia o que recebe**: códigos HEX da análise e trechos do RAG sobre doenças apareciam na resposta ao cliente.
- **Latência e variação do hardware.** A geração vai de cerca de 48 tokens/s (na tomada, GPU a 80 W) a cerca de 5 tokens/s (bateria em `power-saver`, GPU limitada a 20 W) e cai para cerca de 16 tokens/s quando o CUDA falha após uma suspensão e o Ollama passa para a CPU. Uma consultoria completa leva cerca de 1 minuto no melhor caso.
- **Restrições de arquitetura.** O projeto segue a arquitetura hexagonal: o domínio é Java puro, a aplicação depende só de ports, e Telegram, Kafka e LangChain4j ficam nos adapters.

## Decisão

Integrar a IA como um **pipeline local de dois agentes especializados**, ligados por um **contrato de texto rotulado**, com a lógica de consultoria **determinística no domínio**. O LLM fica responsável apenas por perceber (a análise da foto) e redigir (o texto da consultoria).

1. **IA local via LangChain4j, sem starters.** Os beans `bellaChatModel`, `bellaPictureModel`, `bellaEmbeddingModel`, `ChromaEmbeddingStore` e `bellaRetrievalAugmentor` são declarados em `infrastructure/config/ai/LangChain4jConfig`, e os `@AiService` usam `wiringMode = EXPLICIT`. Os starters do Ollama duplicariam o `ChatModel`.

2. **Agente de percepção (`PictureDescriptionAssistant`).** Usa o modelo em **modo JSON** (`ResponseFormat.JSON`), sem memória, com teto de tokens próprio (`picture.num-predict`). Devolve `photo_status` e um `message` com exatamente 7 linhas rotuladas (`Tom de pele:`, `Subtom:`, `Tipo de pele:`, `Contraste:`, `Estação provável:`, `Cores observadas:`, `Limitações:`). O prompt usa placeholders, sem exemplos concretos, e cores por nome, sem HEX. O adapter valida os rótulos (`ANALYSIS_LABELS`) e emite um WARN quando o contrato é quebrado.

3. **Regras de consultoria no domínio (`domain/service/ConsultationGuide`).** O mapeamento subtom → fundo da base, estação → paleta, contraste → intensidade e tipo de pele → cuidados é código Java puro e testado. O `ReplyToMessageService` anexa essas "Orientações da consultoria" à análise, escrita como um pedido do próprio cliente.

4. **Agente de redação (`BellaBotAssistant`).** Usa o modelo **sem modo JSON**, preservando o raciocínio que dá qualidade ao texto, com memória por chat e RAG no ChromaDB. As salvaguardas são:
   - o prompt termina com um separador explícito antes da mensagem do cliente;
   - os trechos do RAG chegam num bloco `[MATERIAL DE CONSULTA] … [FIM DO MATERIAL]`, tratado como material a não copiar;
   - produtos, preços e links só podem ser citados se estiverem no material;
   - a resposta tem um limite de tamanho, e o bloco final de aviso e contato vem escrito literalmente no prompt.

5. **Limites operacionais explícitos.**
   - **Tokens:** `num-predict` 3072 no chat e 2048 na análise de foto, o que comporta o raciocínio e ainda corta loops.
   - **Tempo:** `OLLAMA_TIMEOUT` de 300s e nenhum retry no chat, porque repetir uma chamada que estourou o timeout só multiplica a espera.
   - **Raciocínio:** removido da resposta (`removeThinking`). Se a resposta ficar vazia, o cliente recebe uma mensagem padrão.
   - **Experiência no Telegram:** o "digitando..." é renovado a cada 4s enquanto a IA processa, e mensagens longas são divididas em ponto natural, com as tags HTML equilibradas entre as partes.

6. **Eventos só de sucesso.** Apenas o fluxo de foto publica no Kafka, e só depois de a IA responder e a mensagem ser entregue. O `reply` vai como texto puro, e uma falha na publicação não afeta o cliente.

## Consequências

**Positivas**

- As fotos e os dados do cliente não saem da máquina, e não há custo por chamada.
- A parte da consultoria que precisa estar sempre certa (base, cores, cuidados) passou a acertar sempre: 6 de 6 contra 4 de 6 com a tabela no prompt. Ela é testável sem LLM e muda com um commit, não com ajuste de prompt.
- Cada agente tem uma responsabilidade estreita. O de percepção devolve dados estruturados validados, e o de redação só escreve, o que é o ponto forte de um modelo pequeno.
- O contrato rotulado deixa o acoplamento entre os agentes explícito e observável: um rótulo ausente gera WARN no log.
- O domínio e as ports continuam sem dependência de LangChain4j, Telegram ou Kafka. A troca do modelo, ou um provedor remoto, afeta só os adapters e os prompts.

**Negativas**

- **Latência alta.** Uma consultoria por foto exige duas chamadas ao modelo e leva cerca de 1 minuto na tomada, por causa do raciocínio obrigatório. Na bateria em `power-saver`, a resposta não termina dentro dos 300s e o cliente recebe a mensagem de falha.
- **Uma única GPU atende tudo em série:** os usuários, a carga do RAG na subida e qualquer avaliação de prompt. Pedidos simultâneos entram em fila.
- **O contrato está espalhado** por quatro pontos que precisam mudar juntos: `picture-description-system.md`, `bella-system.md`, `ANALYSIS_LABELS` e `ConsultationGuide`.
- **O conhecimento de colorimetria fica em código.** Ajustar uma paleta exige deploy, e não basta editar o prompt.
- **A qualidade do texto ainda é probabilística.** Nas avaliações ainda aparecem títulos sem emoji, o aviso omitido de vez em quando e, raramente, texto depois do bloco de contato. Mudanças de prompt precisam ser avaliadas com várias rodadas, contra o Ollama, com análises de exemplo e trechos reais do Chroma.

**Riscos e pontos em aberto**

- **Disponibilidade do Ollama.** Após uma suspensão, o CUDA pode falhar e o Ollama passa para a CPU sem avisar a aplicação. Falta um health check que detecte modelo sem VRAM.
- **Memória de chat volátil.** A `MessageWindowChatMemory` fica em memória: as conversas se perdem a cada reinício, e não há escala horizontal.
- **Contato provisório.** Os dados da consultora ("Fulana de Tal / 99999-8888") são um placeholder fixo no prompt. Se o modelo de 4B continuar falhando no bloco final, o código pode passar a acrescentar o aviso e o contato, como já faz com o guia.
- **Troca de modelo.** Um modelo com papel de sistema e raciocínio desligável reduziria a latência e várias das salvaguardas de prompt. Isso deve ser um novo ADR, que substitua este em parte.
