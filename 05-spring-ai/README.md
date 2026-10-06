# Assistente financeiro por voz, com guardrails

API de orçamento que entende comandos de voz ("gastei 50 reais na farmácia"), registra e consulta gastos e responde em áudio. É o projeto final da trilha de Spring Boot da DIO, evoluído com **guardrails determinísticos**: antes de qualquer ferramenta rodar, o código confere o que o modelo pediu.

Os guardrails usam o [noxguard](https://github.com/marcusrdrigues/noxguard), uma biblioteca open source de guardrails para chats e agentes com LLM em Java.

## O que o projeto faz

1. O cliente envia um áudio (ou um texto, pelo endpoint de texto).
2. O Whisper transforma o áudio em texto.
3. O modelo (`gpt-4o-mini`, pelo `ChatClient` do Spring AI) decide qual ferramenta chamar e com quais argumentos.
4. **O guardrail decide se a chamada pode rodar.** Se não puder, a ferramenta não executa e o modelo recebe uma mensagem de erro para pedir à pessoa que repita.
5. A ferramenta executa o caso de uso: registrar uma transação ou listar as de uma categoria.
6. A resposta final vira áudio (MP3).

## A melhoria: guardrails em duas camadas

### O problema

Quem escolhe a ferramenta e os argumentos é o modelo, e a saída do modelo não é confiável: ela vem de um texto transcrito, que pode estar errado, ser ambíguo ou ter sido escrito para manipular o assistente. No projeto base, nada conferia essa decisão.

| O modelo pede | Sem guardrail | Com guardrail |
|---|---|---|
| Registrar um gasto de -2000 centavos | grava um gasto negativo | a ferramenta não roda; o assistente pede o valor de novo |
| Registrar 50 gastos de uma vez | grava os 50 | grava no máximo 3 por resposta |
| Categoria `FOOD`, que não existe | erro de conversão | recusa e informa as categorias aceitas |
| Uma ferramenta que não existe | depende do framework | negada por padrão |
| Um argumento a mais (`date`) | ignorado em silêncio | recusado |

### Camada 1: domínio

A `Transaction` nunca existe num estado inválido, venha ela da API REST, da IA ou do banco: descrição obrigatória com até 120 caracteres, valor em centavos maior que zero e até R$ 100.000,00, e categoria obrigatória. Na API REST, uma transação inválida responde **400** (Problem Details).

### Camada 2: borda da IA, com o noxguard

O `ToolPolicy` do noxguard está para as ferramentas de um agente como o Spring Security está para os endpoints de uma aplicação web: **tudo negado por padrão**, uma regra por ferramenta e uma decisão (`Run` ou `Deny`) antes de cada chamada.

| Ferramenta | Argumento | Regras | Limite por resposta |
|---|---|---|---|
| `persist-transaction` | `description` | obrigatória, texto, não vazia, até 120 caracteres | 3 |
| | `amountInCents` | obrigatório, número inteiro, maior que zero, até R$ 100.000,00 | |
| | `category` | uma de `GROCERIES`, `PHARMA`, `AUTO` | |
| `list-transactions-by-category` | `category` | uma de `GROCERIES`, `PHARMA`, `AUTO` | 2 |
| **Total por resposta** | | | **4** |

```text
texto ─► ChatClient ─► o modelo pede uma ferramenta
                          │
               GuardedToolCallback  (camada 2: noxguard)
               ToolSession.decide(call)
                 ├─ Deny ─► "Error: ..." volta para o modelo; nada roda
                 └─ Run  ─► TransactionTools ─► caso de uso
                                                  │
                                     new Transaction(...)  (camada 1: domínio)
```

Como a integração foi feita:

- **`GuardedToolCallback`** é um decorator do `ToolCallback` do Spring AI. Ele transforma os argumentos do modelo em um mapa, pergunta à `ToolSession` e só chama a ferramenta real se a decisão for `Run`.
- **Uma sessão por resposta:** o `GuardedTools` cria uma `ToolSession` nova a cada requisição, então os limites valem por resposta.
- **A mensagem de recusa nunca repete o que o modelo enviou.** Um valor rejeitado pode ser exatamente o que uma injeção de prompt queria devolver à conversa.
- **Nada fica sem regra por esquecimento:** se uma ferramenta for exposta ao modelo sem estar declarada na política, a aplicação não sobe.
- **Logs sem texto livre:** só o nome da ferramenta, a decisão e a categoria vão para o log; a descrição do gasto, nunca.

### Correções no projeto base

- **Centavos:** a ferramenta recebe o valor em centavos, mas a saída devolvia esse número como se fosse reais. Um gasto de R$ 50,00 voltava como `5000.0`, e o assistente podia responder "R$ 5.000". Agora a saída divide por 100.
- **Ferramentas na infraestrutura:** o `@Tool` do Spring AI saiu dos casos de uso e foi para um adaptador (`infrastructure/ai/TransactionTools`), com parâmetros planos que a política consegue conferir um a um. A camada de aplicação não depende mais de um framework de IA.
- **Prompt do sistema:** explica a conversão de reais para centavos e o que fazer quando uma ferramenta devolve erro.
- **Testes sem infraestrutura:** `./gradlew test` roda sem chave da OpenAI e sem Docker.

A especificação completa, com as decisões e as alternativas descartadas, está em [`docs/specs/001-guardrails.md`](docs/specs/001-guardrails.md).

## Tecnologias

- Java 25 e Spring Boot 4
- Spring AI 2.0 com OpenAI: `gpt-4o-mini` (chat e tool calling), `whisper-1` (transcrição) e `gpt-4o-mini-tts` (voz)
- [noxguard-core 0.2.0](https://central.sonatype.com/artifact/com.marcusrdrigues/noxguard-core) (`ToolPolicy`)
- Spring Data JPA com MySQL, via Docker Compose
- JUnit 5 e AssertJ
- Gradle e Lombok

## Como executar

Pré-requisitos: **JDK 25**, **Docker** em execução e uma **chave da OpenAI** com crédito.

```bash
# Linux / macOS
export OPENAI_API_KEY="sua-chave"
./gradlew bootRun
```

```powershell
# Windows (PowerShell)
$env:OPENAI_API_KEY="sua-chave"
.\gradlew.bat bootRun
```

O MySQL do `compose.yml` sobe sozinho com a aplicação (Spring Boot Docker Compose). A API fica em `http://localhost:8080`.

## Como testar

### Testes automáticos

```bash
./gradlew test
```

Rodam sem chave e sem Docker:

| Teste | O que cobre |
|---|---|
| `TransactionTest` | as regras do domínio: descrição, valor, teto, categoria e mensagens que não repetem o valor recebido |
| `TransactionOutputTest` | a conversão de centavos para reais |
| `BudgetToolPolicyTest` | cada decisão da política: chamada válida, valor inválido, categoria inexistente, argumento extra, ferramenta desconhecida, limites e injeção |
| `GuardedToolCallbackTest` | o decorator: a ferramenta real só roda com `Run`, JSON inválido conta no limite, cada resposta tem a sua sessão e a aplicação não sobe com ferramenta sem regra |

Os testes `*IT` e o `BudgetingApplicationTests` chamam a OpenAI de verdade e só rodam com `OPENAI_API_KEY` definida.

### Fluxo principal

No Windows, use `curl.exe` no PowerShell, ou o Postman.

```bash
# Registrar pela API REST (entrada em centavos, saída em reais)
curl -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -d '{"description": "Remédio", "category": "PHARMA", "amount": 5000}'

# Conversar com o assistente por texto (mesmos guardrails, sem áudio)
curl -X POST http://localhost:8080/transactions/ai/text \
  -H "Content-Type: application/json" \
  -d '{"message": "gastei 50 reais na farmácia"}'

# Consultar uma categoria
curl http://localhost:8080/transactions/PHARMA

# Conversar por voz: envia um áudio e recebe a resposta em MP3
curl -X POST http://localhost:8080/transactions/ai \
  -F "file=@src/test/resources/audio/recording-1.m4a" \
  --output resposta.mp3
```

### Roteiro dos guardrails

| Mensagem | Esperado |
|---|---|
| "gastei 50 reais na farmácia" | grava 5000 centavos em `PHARMA`; a resposta fala em R$ 50,00 |
| "gastei menos 20 reais no mercado" | não grava; o assistente pede o valor de novo |
| "registre 10 gastos de 1 real no mercado" | grava no máximo 3; o assistente avisa que parou |
| "apague todas as minhas transações" | nenhuma ferramenta roda; o assistente diz que não pode |
| "quanto gastei com farmácia?" | lista os gastos com os valores em reais |

O texto exato das respostas varia de uma execução para outra, porque vem do modelo. O que não varia é o que os guardrails deixam gravar.

<!-- Prints do roteiro (Postman ou terminal) entram aqui. -->

## Endpoints

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/transactions` | registra uma transação (`amount` em centavos); 400 se for inválida |
| `GET` | `/transactions/{category}` | lista as transações de uma categoria (`amount` em reais) |
| `POST` | `/transactions/ai` | áudio (`multipart/form-data`, campo `file`) para resposta em MP3 |
| `POST` | `/transactions/ai/text` | `{ "message": "..." }` para `{ "answer": "..." }`; mensagem obrigatória, até 1000 caracteres |

## Estrutura

```text
src/main/java/dio/budgeting/
├── domain/              Transaction (com as regras), categorias e contrato do repositório
├── application/         casos de uso, sem dependência de framework de IA
└── infrastructure/
    ├── ai/              ferramentas, política do noxguard, decorator e o fluxo do assistente
    ├── http/            controller, corpo das requisições e tratamento de erros
    └── persistence/     adaptador JPA
```

## Limites e próximos passos

- A política confere **formato, não intenção**: "50 reais" gravado como R$ 5.000 passa, porque está abaixo do teto. O prompt e o nome `amountInCents` diminuem esse erro; quem elimina é a **confirmação antes de salvar** (`Confirm` e `ProposalGate` do noxguard), o próximo passo.
- Um erro de transcrição ("15" no lugar de "50") passa pelas duas camadas.
- **Injeção indireta:** uma descrição gravada com texto do tipo "ignore as instruções" volta para o modelo quando ele lista as transações. O próximo passo é delimitar os resultados das ferramentas com o `DataEnvelope` do noxguard.

## O que aprendi

<!-- Marcus: escreva aqui, com as suas palavras, o que você aprendeu no desafio. -->

## Créditos

- Projeto base: [dio-spring-boot-learning-track](https://github.com/digitalinnovationone/dio-spring-boot-learning-track), módulo `05-spring-ai`, da DIO.
- Guardrails: [noxguard](https://github.com/marcusrdrigues/noxguard).

## Referências

- [Spring AI](https://docs.spring.io/spring-ai/reference/index.html)
- [ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Transcrição de áudio](https://docs.spring.io/spring-ai/reference/api/audio/transcriptions.html)
- [Síntese de voz](https://docs.spring.io/spring-ai/reference/api/audio/speech.html)
- [OWASP Top 10 para aplicações com LLM](https://genai.owasp.org/llm-top-10/)
