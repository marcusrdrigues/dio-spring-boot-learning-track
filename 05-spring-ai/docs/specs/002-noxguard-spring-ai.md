# Spec 002: trocar o decorator próprio pelo `noxguard-spring-ai`

- **Status:** aprovada (06/10/2026)
- **Projeto:** `05-spring-ai` (API de orçamento por voz, Desafio de Projeto DIO)
- **Dependência trocada:** `com.marcusrdrigues:noxguard-core:0.2.0` sai; entra `com.marcusrdrigues:noxguard-spring-ai:0.4.0`, que traz o `noxguard-core` 0.4.0
- **Dependência atualizada:** Spring AI de `2.0.0-M4` (milestone) para `2.0.1` (versão final)

## 1. Contexto

Na spec 001, a borda da IA ganhou um decorator próprio: o `GuardedToolCallback` embrulha cada `ToolCallback` do Spring AI e pergunta ao `ToolPolicy` do noxguard antes de a ferramenta rodar, e o `GuardedTools` dá a cada resposta uma sessão nova. Funcionou. No roteiro real, dos cinco gastos de uma mesma mensagem, a 4ª e a 5ª chamadas foram negadas com `reason=LIMIT`.

Esse decorator virou o módulo `noxguard-spring-ai` na versão 0.4 do noxguard (spec `docs/specs/0.4-spring-ai.md` do noxguard). Este projeto passa a usar o módulo e vira o exemplo real dele.

## 2. Por que trocar

| # | Hoje, no decorator próprio | Com o módulo |
|---|---|---|
| T1 | O JSON inválido é contado com `session.decide(ToolCall.of(nome))`. Funciona por acaso: numa ferramenta sem argumento obrigatório, a decisão seria `Run` e gastaria a cota dela | `session.invalidArguments(nome)`: nega, conta no total da resposta e nunca na cota da ferramenta |
| T2 | O `Confirm` vira negação, fixo no código | A escolha é explícita (`ConfirmMode.DENY` ou `HOLD`). Hoje nenhuma ferramenta usa `confirm()`, então nada muda; quando a "confirmação antes de salvar" chegar, é só trocar para `HOLD` |
| T3 | Usa `JsonParser.fromJson`, que o Spring AI 2.0.1 marcou para remoção | O módulo usa `JsonHelper.fromJsonToMap`, o substituto indicado |
| T4 | Uma chamada que chegue depois de a resposta acabar ainda roda | Depois de `release`, a chamada é negada e a ferramenta não roda |
| T5 | 2 classes e 1 teste de código de infraestrutura que é igual ao de qualquer app com Spring AI | O código fica na biblioteca, testado lá; aqui fica só o que é do projeto: a política e o log |

## 3. Objetivo

Trocar o decorator próprio pelo módulo **sem mudar o comportamento** que a spec 001 especificou e o roteiro do README comprovou. A política (`GuardrailConfiguration.policy()`), o domínio, os casos de uso e o prompt não mudam.

## 4. Fora do escopo

- A confirmação antes de salvar (`ConfirmMode.HOLD`). Continua como próximo passo, agora mais curto.
- Mudar a política, o prompt ou os endpoints.
- Subir o Spring Boot de versão (continua 4.0.5).

## 5. Preservar o que foi entregue

O projeto foi entregue para o certificado da DIO. Antes da troca, o estado entregue ganha uma tag, `entrega-dio`, no commit atual (`75b9a16`). Quem quiser ver exatamente o que foi entregue abre a tag; o `main` segue evoluindo. O README ganha uma linha avisando isso, com o link da tag.

## 6. Desenho

```text
texto ─► ChatClient ─► o modelo pede uma ferramenta
                          │
               noxguard-spring-ai  (camada 2)
               GuardedToolCallbacks.forNewAnswer()
                 ├─ Deny ─► "Error: ..." volta para o modelo; nada roda
                 └─ Run  ─► TransactionTools ─► caso de uso
                                                  │
                                     new Transaction(...)  (camada 1: domínio)
```

- **`GuardrailConfiguration`** passa a criar um bean `GuardedToolCallbacks` com `GuardedToolCallbacks.builder(policy).tools(ToolCallbacks.from(transactionTools)).listener(new ToolDecisionLog()).build()`. Sem `onConfirm`: nenhuma ferramenta do projeto usa `confirm()`. A verificação na inicialização (ferramenta sem regra impede a aplicação de subir) agora é do módulo.
- **`ToolDecisionLog`** (novo, em `infrastructure/ai`) implementa o `ToolDecisionListener` com o SLF4J e mantém as mesmas linhas de log de hoje: `Tool call allowed: tool=... args=...` (só a categoria) e `Tool call denied: tool=... reason=... argument=...`. A descrição, que é texto livre da pessoa, continua fora do log.
- **`BudgetAssistant`** passa a usar `.toolCallbacks(guardedToolCallbacks.forNewAnswer().callbacks())`.
- **Saem** o `GuardedToolCallback`, o `GuardedTools` e o `GuardedToolCallbackTest`.

### Diferença visível

Só a mensagem de JSON inválido que volta para o modelo muda: de `Error: the arguments are not valid JSON.` para `Error: the arguments are not a valid JSON object; send an object with: description, amountInCents, category.` O modelo não mostra essa mensagem à pessoa, e a nova diz o que mandar.

## 7. Mudanças por arquivo

| Arquivo | Mudança |
|---|---|
| `build.gradle` | `spring-ai-bom` de `2.0.0-M4` para `2.0.1`; `noxguard-core:0.2.0` vira `noxguard-spring-ai:0.4.0` |
| `infrastructure/ai/GuardrailConfiguration.java` | bean `GuardedToolCallbacks` no lugar do `GuardedTools` |
| `infrastructure/ai/ToolDecisionLog.java` | novo: o log das decisões |
| `infrastructure/ai/BudgetAssistant.java` | usa `GuardedToolCallbacks` |
| `infrastructure/ai/GuardedToolCallback.java`, `GuardedTools.java` | removidos |
| `test/.../GuardedToolCallbackTest.java` | vira `GuardedToolCallbacksWiringTest`: os casos G1 a G5 da spec 001 contra a configuração real do projeto |
| `test/.../ToolDecisionLogTest.java` | novo: o log não contém a descrição nem os argumentos de uma chamada negada |
| `.github/workflows/05-spring-ai.yml` | novo: roda `./gradlew test` no Java 25 a cada push que mexe em `05-spring-ai` |
| `README.md` | a seção "Como a integração foi feita" e "Tecnologias" passam a citar o módulo; a linha da tag `entrega-dio` |

## 8. Casos de teste

### 8.1 Automáticos (rodam sem chave e sem Docker)

`BudgetToolPolicyTest` (P1 a P9) e os testes de domínio não mudam.

**`GuardedToolCallbacksWiringTest`** (a configuração do projeto com uma ferramenta falsa)

| # | Caso | Esperado |
|---|---|---|
| G1 | `Run` | a ferramenta roda uma vez e o resultado volta |
| G2 | `Deny` | a ferramenta não roda; volta "Error: ..." |
| G3 | JSON inválido | a ferramenta não roda; a mensagem lista os argumentos declarados; a chamada conta no total |
| G4 | resposta nova | os contadores recomeçam do zero |
| G5 | ferramenta exposta e não declarada na política | `IllegalStateException` na criação, com o nome da ferramenta |

**`ToolDecisionLogTest`**

| # | Caso | Esperado |
|---|---|---|
| L1 | `Run` de `persist-transaction` | o log tem a ferramenta e a categoria, e não tem a descrição |
| L2 | `Deny` | o log tem o motivo e o nome do argumento, e não tem o valor enviado |

### 8.2 Manual, com a chave da OpenAI

Repetir o roteiro dos guardrails do README (M1 a M5 da spec 001, mais os cinco gastos numa mensagem) e conferir que o resultado é o mesmo.

## 9. Critérios de aceite

1. O CI novo passa: `./gradlew test` sem `OPENAI_API_KEY` e sem Docker, com os casos da seção 8.1.
2. A tag `entrega-dio` aponta para o estado entregue, e o README cita a tag.
3. O roteiro manual dá o mesmo resultado de antes.

## 10. Decisões

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| Subir o Spring AI para 2.0.1 | Ficar no 2.0.0-M4 | O módulo usa o `JsonHelper`, que só existe a partir do 2.0.0 final. E uma milestone não deve ficar em projeto que serve de exemplo |
| Bean `GuardedToolCallbacks` montado à mão | O `noxguard-spring-boot-starter` | Mesmo motivo da spec 001: as propriedades não expressam as regras numéricas (maior que zero, teto em centavos). A política continua em Java |
| Log num `ToolDecisionListener` do projeto | Log dentro da biblioteca | A biblioteca não depende de biblioteca de log; cada app loga do seu jeito |
| CI só para o `05-spring-ai` | CI para a trilha inteira | Os módulos 00 a 04 são do projeto base e não foram mexidos |

## 11. Plano de commits

1. `docs(spec): especifica a troca do decorator pelo noxguard-spring-ai`
2. `build(ia): atualiza o Spring AI para 2.0.1 e troca o noxguard-core pelo noxguard-spring-ai`
3. `refactor(ia): usa o GuardedToolCallbacks do noxguard no lugar do decorator próprio`
4. `test(ia): cobre a ligação com o noxguard e o log das decisões`
5. `ci: roda os testes do 05-spring-ai a cada push`
6. `docs(readme): cita o noxguard-spring-ai e a tag da entrega`

A tag `entrega-dio` é criada antes do commit 1.
