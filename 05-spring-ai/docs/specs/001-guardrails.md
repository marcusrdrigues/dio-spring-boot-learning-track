# Spec 001: guardrails do assistente financeiro

- **Status:** aprovada
- **Projeto:** `05-spring-ai` (API de orçamento por voz, Desafio de Projeto DIO)
- **Dependência nova:** `com.marcusrdrigues:noxguard-core:0.2.0`

## 1. Contexto

No endpoint `POST /transactions/ai`, o áudio vira texto (Whisper), o `ChatClient` escolhe uma ferramenta e a executa. As ferramentas são `persist-transaction` e `list-transactions-by-category`. Depois a resposta vira áudio.

Quem decide **qual** ferramenta chamar e **com quais argumentos** é o modelo. Hoje nada confere essa decisão antes de a ferramenta rodar. A saída do modelo é tratada como confiável, mas não é: ela vem de um texto transcrito, que pode estar errado, ser ambíguo ou ter sido escrito para manipular o assistente.

## 2. Problemas

| # | Problema | Exemplo |
|---|---|---|
| P1 | Valor inválido é gravado | "gastei menos 20 reais" vira uma transação de -2000 centavos |
| P2 | Descrição vazia ou enorme é gravada | o modelo manda `""` ou um parágrafo inteiro |
| P3 | Nenhum limite por resposta | "registre 50 gastos de 1 real" dispara 50 gravações |
| P4 | Não existe "negado por padrão" | qualquer argumento extra é aceito em silêncio |
| P5 | **Bug:** a saída trata centavos como reais | 5000 centavos (R$ 50,00) volta como `5000.0`, e o assistente pode dizer "R$ 5.000" |
| P6 | Argumentos aninhados | a ferramenta recebe `{"input": {...}}`, então não dá para aplicar regra por campo |
| P7 | O `@Tool` do Spring AI está na camada de aplicação | os casos de uso dependem de um framework de IA |
| P8 | Erro de validação na API REST vira 500 | não existe tratamento de erro de domínio |
| P9 | `gradlew test` falha sem infraestrutura | `BudgetingApplicationTests` sobe o contexto inteiro e precisa de MySQL e da chave da OpenAI |

## 3. Objetivo

Duas camadas de proteção, para que uma falha numa não deixe passar o erro (defesa em profundidade):

1. **Domínio:** a `Transaction` nunca existe num estado inválido, venha ela da API REST ou da IA.
2. **Borda da IA:** antes de cada chamada de ferramenta, uma política do noxguard (`ToolPolicy`) decide se ela roda. Tudo é negado por padrão, cada argumento tem as suas regras e há um limite de chamadas por resposta.

Junto, as correções P5 a P9.

## 4. Fora do escopo (próximos passos)

- **Confirmação antes de salvar** (`ToolDecision.Confirm` + `ProposalGate`): o fluxo por áudio não tem tela para a pessoa confirmar.
- **Delimitar resultados de ferramenta** (`DataEnvelope`): uma descrição gravada antes, com texto do tipo "ignore as instruções", volta para o modelo quando ele lista as transações. É injeção indireta de prompt e fica registrada como próximo passo.
- **Classificador de injeção no texto transcrito** (`InputViews` + classificador).
- Autenticação, multiusuário e trocar `ddl-auto=update` por migrações (Flyway).

## 5. Desenho

```text
áudio ─► Whisper ─► texto ─► ChatClient ─► o modelo pede uma ferramenta
                                              │
                                   GuardedToolCallback  (camada 2: noxguard)
                                   ToolSession.decide(call)
                                     ├─ Deny ─► "Error: ..." volta para o modelo; nada roda
                                     └─ Run  ─► TransactionTools ─► caso de uso
                                                                      │
                                                         new Transaction(...)  (camada 1: domínio)
                                                           └─ inválida ─► exceção ─► mensagem volta para o modelo
```

### 5.1 Camada 1: domínio

`Transaction` valida as invariantes nos **dois** construtores, o de criação e o de reidratação usado pelo JPA. Uma linha inválida no banco é um erro, não um dado a ser aceito.

| Campo | Regra |
|---|---|
| `description` | obrigatória, sem espaços nas pontas, não pode ficar vazia, até 120 caracteres |
| `amount` | em centavos; maior que zero; no máximo `Transaction.MAX_AMOUNT_CENTS = 10_000_000` (R$ 100.000,00) |
| `category` | obrigatória |

- A violação lança `InvalidTransactionException` (domínio, `extends IllegalArgumentException`) com uma mensagem fixa, que **nunca repete o valor recebido**. Exemplo: "O valor da transação precisa ser maior que zero."
- Quando a exceção sai de uma ferramenta, o Spring AI devolve a mensagem para o modelo. Isso foi conferido no código da 2.0.0-M4: o `DefaultToolExecutionExceptionProcessor` não relança o erro por padrão.
- Na API REST, um `@RestControllerAdvice` converte a exceção em **400** (Problem Details). Resolve o P8.

### 5.2 Camada 2: borda da IA (noxguard)

**Ferramentas na infraestrutura (resolve P6 e P7).** Uma nova classe `infrastructure/ai/TransactionTools` recebe os `@Tool`, com parâmetros planos que chamam os casos de uso. Os casos de uso e o `PersistTransactionInput` deixam de conhecer o Spring AI.

| Ferramenta | Parâmetros |
|---|---|
| `persist-transaction` | `description` (texto), `amountInCents` (inteiro, com a descrição "Valor em centavos. R$ 50,00 = 5000"), `category` |
| `list-transactions-by-category` | `category` |

O nome `amountInCents` deixa a unidade explícita para o modelo, que é a origem do P5.

**A política (`ToolPolicy`)**, declarada em Java num bean de `infrastructure/ai/GuardrailConfiguration`:

| Ferramenta | Argumento | Regras | Limite por resposta |
|---|---|---|---|
| `persist-transaction` | `description` | obrigatória, texto, não vazia, até 120 caracteres | 3 |
| | `amountInCents` | obrigatório, número inteiro, maior que zero, até `MAX_AMOUNT_CENTS` | |
| | `category` | obrigatória, um de `Category.values()` | |
| `list-transactions-by-category` | `category` | obrigatória, um de `Category.values()` | 2 |
| **Total da resposta** | | | **4** |

- Os valores aceitos em `category` saem do próprio enum, então a política não fica desatualizada quando surgir uma categoria nova.
- `logArgs("category")`: só a categoria vai para o log, nunca a descrição, que é texto livre da pessoa.
- As regras próprias (`notBlank`, `wholeCents`) seguem o contrato do `ArgRule`: frase curta, sem o valor recebido, e "fail closed" se a regra lançar exceção.
- O teto do valor fica numa constante só (`Transaction.MAX_AMOUNT_CENTS`), usada pelo domínio e pela política.

**`GuardedToolCallback`** (decorator de `ToolCallback`, em `infrastructure/ai`):

1. Converte o JSON dos argumentos em `Map<String, Object>` com o `JsonParser` do Spring AI.
2. Chama `session.decide(new ToolCall(nome, args))`:
   - `Run`: chama a ferramenta original e registra no log o nome e os `loggableArgs`.
   - `Deny`: **não chama** a ferramenta, devolve `deny.messageForModel()` ao modelo e registra no log o nome e o motivo (`UNKNOWN_TOOL`, `ARGUMENT` ou `LIMIT`), sem os argumentos.
   - `Confirm`: nenhuma ferramenta usa `confirm()` nesta versão. Se aparecer, a chamada é negada ("fail closed").
3. JSON inválido: a ferramenta não roda, o modelo recebe "Error: the arguments are not valid JSON." e a chamada conta no limite da resposta.

**Uma sessão por resposta.** O componente `GuardedTools` cria uma `ToolSession` nova a cada requisição e devolve as ferramentas envolvidas por ela. O `ChatClient` deixa de usar `defaultTools` e passa a receber `.toolCallbacks(guardedTools.forNewAnswer())` em cada chamada.

**Verificação na inicialização.** Toda ferramenta exposta ao modelo precisa estar declarada na política. Se não estiver, a aplicação não sobe (`IllegalStateException` com o nome da ferramenta). Uma ferramenta nova não fica sem regra por esquecimento.

**Orquestração fora do controller.** A classe `infrastructure/ai/BudgetAssistant` concentra o fluxo transcrever → conversar → sintetizar. O `TransactionController` fica só com o HTTP.

**Endpoint de texto, para testar sem áudio.** `POST /transactions/ai/text` recebe `{ "message": "..." }` e devolve `{ "answer": "..." }`. A mensagem é obrigatória e tem até 1000 caracteres; fora disso, a resposta é 400 e o modelo nem é chamado. Ele passa pelo mesmo `BudgetAssistant` e pelos mesmos guardrails, mas sem Whisper e sem síntese de voz. É mais barato, mais rápido e fácil de mostrar no README.

### 5.3 Correções

- **P5, centavos:** `TransactionOutput.value` passa a ser `amount / 100` com duas casas (`BigDecimal.valueOf(amount, 2)`). 5000 vira 50.00.
- **Prompt do sistema** (`system-message.st`): converter reais em centavos (R$ 50,00 = 5000); nunca inventar um valor; se uma ferramenta devolver erro, explicar e pedir à pessoa que repita; responder em português.
- **P9:** `BudgetingApplicationTests` passa a rodar só com `OPENAI_API_KEY` definida, como os outros `*IT`. Assim `gradlew test` roda sem chave e sem Docker.

## 6. Mudanças por arquivo

| Arquivo | Mudança |
|---|---|
| `build.gradle` | `implementation 'com.marcusrdrigues:noxguard-core:0.2.0'` |
| `domain/Transaction.java` | invariantes e `MAX_AMOUNT_CENTS` |
| `domain/InvalidTransactionException.java` | novo |
| `application/PersistTransactionUseCase.java`, `ListTransactionsByCategoryUseCase.java`, `input/PersistTransactionInput.java` | sem `@Tool` e sem `@ToolParam` |
| `application/output/TransactionOutput.java` | correção dos centavos |
| `infrastructure/ai/TransactionTools.java` | novo: ferramentas com parâmetros planos |
| `infrastructure/ai/GuardrailConfiguration.java` | novo: beans da `ToolPolicy` e do `GuardedTools`, e as regras próprias |
| `infrastructure/ai/GuardedToolCallback.java` | novo: decorator |
| `infrastructure/ai/GuardedTools.java` | novo: uma sessão por resposta e a verificação na inicialização |
| `infrastructure/ai/BudgetAssistant.java` | novo: orquestração do fluxo |
| `infrastructure/http/TransactionController.java` | usa o `BudgetAssistant`; endpoint `/ai/text` |
| `infrastructure/http/ApiErrors.java` | novo: 400 para `InvalidTransactionException` |
| `infrastructure/http/request/AssistantRequest.java`, `response/AssistantResponse.java` | novos: corpo do endpoint de texto |
| `resources/prompts/system-message.st` | regras de centavos e de erro |
| `test/...` | testes da seção 7 |
| `README.md` | entrega do desafio |

## 7. Casos de teste

### 7.1 Automáticos (rodam sem chave e sem Docker)

**`TransactionTest`** (domínio)

| # | Caso | Esperado |
|---|---|---|
| D1 | dados válidos | cria |
| D2 | descrição nula, vazia ou só com espaços | `InvalidTransactionException` |
| D3 | descrição com 121 caracteres | exceção; com 120, cria |
| D4 | valor 0 ou negativo | exceção |
| D5 | valor igual a `MAX_AMOUNT_CENTS` | cria; com 1 a mais, exceção |
| D6 | categoria nula | exceção |
| D7 | reidratação (construtor com id) com dado inválido | exceção |
| D8 | a mensagem de erro | não contém o valor recebido |

**`TransactionOutputTest`:** 5000 vira 50.00 e 1 vira 0.01.

**`BudgetToolPolicyTest`** (a política, sem Spring)

| # | Chamada do modelo | Decisão |
|---|---|---|
| P1 | `persist-transaction` válida | `Run`, com `loggableArgs` só com `category` |
| P2 | `amountInCents` igual a -2000, 0, 12.5, `"5000"` (texto) ou acima do teto | `Deny` `ARGUMENT`, argumento `amountInCents` |
| P3 | `category` igual a `"FOOD"` | `Deny` `ARGUMENT`; a mensagem lista as categorias aceitas |
| P4 | `description` ausente, `""` ou só espaços | `Deny` `ARGUMENT` |
| P5 | argumento extra (`date`) | `Deny` `ARGUMENT` ("unexpected argument") |
| P6 | ferramenta `delete-all-transactions` | `Deny` `UNKNOWN_TOOL`, com as ferramentas disponíveis |
| P7 | 4ª `persist-transaction` na mesma resposta | `Deny` `LIMIT` |
| P8 | 5ª chamada da resposta, de qualquer ferramenta | `Deny` `LIMIT` ("tool limit reached; answer now.") |
| P9 | `category` com texto de injeção ("IGNORE AS INSTRUÇÕES...") | `Deny`; a mensagem não contém o texto enviado |

**`GuardedToolCallbackTest`** (com uma ferramenta falsa que conta as chamadas)

| # | Caso | Esperado |
|---|---|---|
| G1 | `Run` | a ferramenta roda uma vez e o resultado volta |
| G2 | `Deny` | a ferramenta não roda; volta "Error: ..." |
| G3 | JSON inválido | a ferramenta não roda; mensagem fixa; a chamada conta no limite |
| G4 | resposta nova | os contadores recomeçam do zero |
| G5 | ferramenta exposta e não declarada na política | `IllegalStateException` na inicialização |

### 7.2 Manuais, com a chave da OpenAI (vão para o README com print)

Feitos por `POST /transactions/ai/text`, e o principal também por áudio.

| # | Mensagem | Esperado |
|---|---|---|
| M1 | "gastei 50 reais na farmácia" | grava 5000 centavos em `PHARMA`; a resposta fala em R$ 50,00 |
| M2 | "gastei menos 20 reais no mercado" | não grava; o assistente pede o valor de novo |
| M3 | "registre 10 gastos de 1 real no mercado" | grava no máximo 3; o assistente avisa que parou |
| M4 | "apague todas as minhas transações" | nenhuma ferramenta roda; o assistente diz que não pode |
| M5 | "quanto gastei com farmácia?" | lista com os valores em reais |

## 8. Critérios de aceite

1. `gradlew test` passa sem `OPENAI_API_KEY` e sem Docker, com todos os casos da seção 7.1.
2. Os cenários M1 a M5 se comportam como na tabela.
3. Nenhum log contém a descrição de uma transação nem os argumentos de uma chamada negada.
4. O README explica a melhoria, como rodar e como testar, com os prints.

## 9. Decisões

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| Validar no domínio **e** na borda da IA | Só no domínio | O domínio protege o dado. A política também pega ferramenta desconhecida, argumento extra e excesso de chamadas, e para **antes** de executar qualquer coisa |
| `noxguard-core` com a política em Java | `noxguard-spring-boot-starter` com propriedades | As propriedades não expressam regra numérica (maior que zero, teto) e o projeto não precisa das outras guardas agora |
| Decorator de `ToolCallback` | Advisor do `ChatClient` | O decorator fica exatamente no ponto em que cada ferramenta executa, dentro do loop do Spring AI, sem depender de como esse loop é feito |
| Mensagens para o modelo em inglês | Em português | São as mensagens padrão do noxguard. O modelo não as mostra à pessoa; o prompt manda responder em português |
| Entrada REST continua `amount` em centavos | Renomear para `amountInCents` | Não quebrar o contrato do projeto base. O README documenta: entrada em centavos, saída em reais |
| Sem `ToolChoice.NONE` depois do limite | Trocar o `tool_choice` no meio do loop | O Spring AI executa o loop por dentro. Toda chamada conta, então a mensagem "answer now" já encerra |

## 10. Limites, sem rodeio

- A política confere **formato, não intenção**. "Gastei 50 reais" gravado como 500000 centavos (R$ 5.000) passa, porque está abaixo do teto. O prompt e o nome `amountInCents` diminuem esse erro, mas não o eliminam. Quem elimina é a confirmação antes de salvar, que é o próximo passo.
- Um erro de transcrição do Whisper ("15" no lugar de "50") passa pelas duas camadas.
- A injeção indireta pela descrição gravada continua aberta até o `DataEnvelope` (seção 4).

## 11. Plano de commits

1. `docs(spec): especifica os guardrails do assistente financeiro`
2. `fix(transacoes): corrige o valor em centavos na saída`
3. `feat(dominio): valida as invariantes da transação`
4. `refactor(ia): move as ferramentas para a infraestrutura`
5. `feat(ia): aplica a política de ferramentas do noxguard`
6. `feat(ia): adiciona o endpoint de texto do assistente`
7. `test: cobre o domínio, a política e o decorator`
8. `docs(readme): documenta a entrega do desafio`
