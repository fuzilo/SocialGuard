SocialGuard - Classificador Automatizado de Conteúdo.

## Processamento assíncrono de lotes

O `POST /api/v1/batches` persiste o lote como `PENDING` e publica no RabbitMQ somente o `batchId` (corpo da mensagem como texto simples). A fila durável `socialguard.batch.processing` é ligada ao exchange `socialguard.batch` pela routing key `batch.process`. Mensagens rejeitadas sem requeue são encaminhadas para `socialguard.batch.failed`; o consumidor OpenMock (API SocialGuard local: `http://localhost:8084`, OpenMock: porta `8082`) deve limitar retries transitórios e confirmar a mensagem somente depois de persistir o resultado ou reconhecer que o lote já foi reivindicado/concluído.

O OpenMock é um consumidor externo. Para cada mensagem, deve:

1. Consultar `GET /api/v1/batches/{id}` para carregar do SocialGuard os dados que não estão na mensagem.
2. Chamar `POST /api/v1/batches/{id}/claim`. Só processar quando a resposta tiver `claimed: true`; quando `false`, confirmar a mensagem sem repetir o processamento.
3. Persistir todas as classificações com `PUT /api/v1/batches/{id}/results`, usando o `id` de cada comentário retornado pelo GET:

```json
{
  "comments": [
    { "id": 12, "inferenceResult": "SPAM" }
  ],
  "tokensConsumed": 1500,
  "llmModel": "gpt-4"
}
```

O endpoint de resultados valida que cada comentário do lote apareça uma vez, mantém os dados originais e conclui o lote. Repetir o mesmo envio após conclusão é idempotente; resultados diferentes ou transições inválidas retornam `409 Conflict`. Para falha permanente, o OpenMock usa `POST /api/v1/batches/{id}/failure`; o lote só muda de `PROCESSING` para `FAILED`.

Se a publicação direta falhar após a persistência, o `POST` retorna `503 Service Unavailable`, incluindo `batchId` no Problem Detail. O registro continua `PENDING`; após a recuperação do broker, reenfileire com `POST /api/v1/batches/{id}/enqueue`. Um timeout de confirmação pode ter resultado ambíguo; mensagens duplicadas são seguras por causa da reivindicação atômica. Lotes abandonados em `PROCESSING` também exigem recuperação operacional: retornar o estado para `PENDING` por `PUT /api/v1/batches/{id}` e reenfileirar.

Configuração padrão para execução local: RabbitMQ em `localhost:5672`, usuário/senha `aluno`. Configure `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` e `RABBITMQ_CONFIRM_TIMEOUT`; nomes de exchange/fila/routing key também podem ser substituídos pelas variáveis `BATCH_EXCHANGE`, `BATCH_QUEUE`, `BATCH_ROUTING_KEY`, `BATCH_DLX`, `BATCH_DLQ` e `BATCH_DLX_ROUTING_KEY`.

SocialGuard é um software capaz de orquestrar fluxos de classificação de posts escritos de redes sociais, para moderar conteúdos impróprios. Validações manuais e individuais são custosas e não escalam. Chamadas individuais a LLMs para cada post geram custo alto de token. Necessidade de padronização dos registros e auditoria das decisões de moderação.
O principal objetivo é reduzir custos de operação e consumo de tokens. Isso pode ser obtido, utilizando-se chamadas em lote às APIs dos modelos de IA generativa.

O orquestrador será responsável por:
Receber lotes de posts de clientes;
Agrupá-los em prompts otimizados (batch inference);
Submeter à API do LLM, garantindo resiliência às solicitações;
Persistir status, resultados e métricas de custo;
Disponibilizar relatórios para o cliente.

Principais funcionalidades do sistema:

Cadastro de clientes
cada usuário poderá submeter lotes de posts
visualizar o status das análises
obter o resultado das análises

Submissão de um lote de posts:
envio do lote à API do modelo de LLM

Registro e enfileiramento:
criação de registro de status (PENDENTE, PROCESSANDO, CONCLUÍDO, FALHOU)
registro do lote no banco de dados

Cálculo do custo da classificação:
gerenciar o consumo de tokens para inferência em lote.
estimar economia gerada

Persistência e Notificação:
classificar posts em categorias(ok, spam, violento, sexual)
notificar o usuário da conclusão de uma análise(opcional)


Aspectos técnicos:

CRUD dos arquivos de lote dos posts


Criação de filas de processamento com Spring AMQP / RabbitMQ (1);
Desacoplar a submissão do lote via APIs REST (Spring MVC) do processamento assíncrono (chamada ao LLM), garantindo escalabilidade horizontal e controle de concorrência com Spring Boot.


Gerenciamento do consumo de tokens e persistência com Spring Data (2);
Medir, controlar e otimizar o consumo de tokens, registrando métricas e persistindo relatórios via Spring Data JPA para comprovação da economia.


Gerar logs e observabilidade com Spring Boot Actuator (3);
Garantir a observabilidade técnica e infraestrutural utilizando Spring Boot Actuator e Micrometer para coletar métricas de saúde da aplicação, vazão de filas e estado da resiliência, enquanto os KPIs e métricas de negócio são fornecidos por endpoints REST dedicados consultando o banco de dados via Spring Data JPA.
Grafana


Resiliência na integração com LLMs usando Resilience4j (4).
Garantir a disponibilidade contínua através de padrões de Retry e Circuit Breaker (Resilience4j integrado ao Spring) para contornar oscilações, rate limits e degradações nos provedores de LLM.


Sugestão de APIs

APIs REST da Aplicação (Endpoints Internos do SocialGuard)
POST /api/v1/batches: Recebe e enfileira um lote de posts para classificação assíncrona.
GET /api/v1/batches/{id}: Retorna o status atual do processamento do lote (PENDENTE, PROCESSANDO, CONCLUÍDO, FALHOU).
GET /api/v1/batches/{id}/results: Disponibiliza os resultados da moderação (categoria atribuída, nível de confiança e detalhes do relatório).
GET /api/v1/reports/savings: Exibe o relatório de consumo de tokens e a estimativa de economia financeira obtida.

Métricas e Dashboard
1. Métricas de Negócio e KPIs (Endpoints REST dedicados via Spring Data JPA)
   Volume de Processamento: Total de posts e lotes analisados por período (diário, semanal e mensal).
   Distribuição de Conteúdo Moderado: Porcentagem de posts classificados em cada categoria (OK, Spam, Violento, Sexual).
   Economia Gerada (R$ / US$): Comparativo financeiro entre o custo real das chamadas em lote e o custo estimado de chamadas individuais síncronas.
   Tempo Médio de Atendimento (SLA): Tempo médio desde a submissão do lote até a entrega final dos resultados.

2. Métricas Técnicas e Operacionais (Actuator + Micrometer)
   Vazão e Latência de Filas (Spring AMQP / RabbitMQ): Quantidade de mensagens enfileiradas, taxa de consumo e tempo de permanência na fila.
   Métricas de Resiliência (Resilience4j): Estado dos Circuit Breakers, taxa de requisições rejeitadas por rate limiting e quantidade de retentativas (retries) executadas.
   Taxa de Erro e Sucesso da API LLM: Percentual de chamadas concluídas com sucesso vs. falhas/timeouts nos provedores de LLM.




{
batchId: 1,
companyId: 1,
createdAt: "2024-06-01T10:00:00Z",
updatedAt: "2024-06-01T10:05:00Z",
completedAt: "2024-06-01T10:05:00Z",
status: "CONCLUÍDO",
tokensConsumed: 1500,
LLM_model: "gpt-4",


"comments":
[
{
"postId": 1,
"commentId": 1,
"body": "laudantium enim quasi est quidem magnam voluptate ipsam eos\ntempora quo necessitatibus\ndolor quam autem quasi\nreiciendis et nam sapiente accusantium",
"createdAt": "2024-06-01T10:00:00Z",
"updatedAt": "2024-06-01T10:05:00Z",
} ,
{
"postId": 1,
"id": 2,
"name": "quo vero reiciendis velit similique earum",
"email": "Jayne_Kuhic@sydney.com",
"body": "est natus enim nihil est dolore omnis voluptatem numquam\net omnis occaecati quod ullam at\nvoluptatem error expedita pariatur\nnihil sint nostrum voluptatem reiciendis et"
},
{
"postId": 1,
"id": 3,
"name": "odio adipisci rerum aut animi",
"email": "Nikita@garfield.biz",
"body": "quia molestiae reprehenderit quasi aspernatur\naut expedita occaecati aliquam eveniet laudantium\nomnis quibusdam delectus saepe quia accusamus maiores nam est\ncum et ducimus et vero voluptates excepturi deleniti ratione"
}
]

}