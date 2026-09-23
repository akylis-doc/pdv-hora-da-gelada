# Logs e diagnóstico — versão 1.3.0

## Usar no aplicativo

Entre com um administrador e abra **☰ → Ajustes → Diagnóstico → Abrir Logs**. Tudo é consultado neste aparelho e funciona sem internet. Não há envio automático de logs ao Firebase nem cobrança de leituras de banco por esta tela.

1. Deixe o modo normal para o uso diário. Ative o modo diagnóstico antes de reproduzir um problema quando precisar incluir TRACE/DEBUG.
2. Reproduza a operação e volte aos logs. A lista começa pelos registros mais recentes; LIVE acompanha alterações locais, e Pausar permite analisar com calma.
3. Use os atalhos de erros, warnings, últimos 10 minutos, última hora ou hoje. Os filtros completos incluem nível, categoria, período personalizado, evento, status, trace_id, operation_id, conectividade, sincronização e texto. A busca inclui IDs, mensagens, códigos de erro e nome de produto registrado na consulta do carrinho, sem copiar o cadastro inteiro para o log.
4. Toque num registro para ver os detalhes e copiar. **Ver rastreamento** abre a sequência cronológica, diferenças de tempo e etapas medidas. O intervalo entre registros pode incluir tempo de decisão do operador ou espera offline; não equivale necessariamente ao tempo de processamento.
5. Use **Exportar** para salvar um JSON de todos os registros, erros, filtro atual/período ou rastreamento. Escolha o destino pelo seletor do Android e envie o arquivo ao desenvolvedor pelo meio que preferir.
6. **Limpar** permite apagar TRACE/DEBUG, vencidos ou todos, sempre com confirmação. A limpeza de logs não altera vendas, estoque nem a fila persistente do Firestore.

O resumo mostra erros e warnings de hoje, operações lentas, últimas ocorrências e pendências observadas nos logs retidos. Não é uma contagem autoritativa de toda a fila interna do Firebase. O acesso à tela, cópia e exportação fica restrito ao administrador; uma revogação durante a exportação interrompe a escrita posterior, mas não apaga bytes já exportados.

## Arquitetura e fluxo

`UI / ViewModel / Repository → AppLogger → fila local limitada → worker de IO → journal JSONL privado → StateFlow → página de Logs`.

`AppLogger` e os modelos ficam no módulo `core`, sem dependência Android/Firebase. `LocalAppLogger` oferece a implementação central e `DiagnosticLogStore` fornece consultas, resumo, exportação e limpeza. O Android fornece versão, UID, conectividade e identificador aleatório persistente do aparelho. A inicialização ocorre antes do Firebase.

Os arquivos ficam em `noBackupFilesDir/diagnostics` do aplicativo: journal, preferência do modo, recuperação de crash e índice de operação/rastreamento. Não são dados de negócio e não são enviados à nuvem. O projeto usa o cache persistente do Firestore e comprovantes locais de operações; não possui Room nem uma SyncQueue própria. Os logs acompanham callbacks realmente observados, sem simular consultas, tentativas internas ou conflitos não expostos pelo SDK.

## Modelo e classificação

O JSON usa `snake_case` e inclui, quando aplicável: `id`, `timestamp` (Unix em milissegundos), `level`, `category`, `event`, `message`, `trace_id`, `operation_id`, `entity_id`, `user_id`, `device_id`, `screen`, `component`, `function`, `duration_ms`, `status`, `error_code`, `exception_type`, `stack_trace`, `metadata`, `connectivity_status`, `sync_status`, `app_version`, `build_version`, `device_information` e `sequence` para desempatar eventos no mesmo milissegundo. Causas e exceções suprimidas aparecem no stack trace limitado.

Níveis: **TRACE, DEBUG, INFO, WARNING, ERROR, CRITICAL**. O modo normal grava INFO em diante; o diagnóstico habilita também TRACE/DEBUG. Nível e status aparecem por escrito, além das cores.

Categorias: **APP, UI, AUTH, CASH_REGISTER, CART, PRODUCT, SALE, PAYMENT, STOCK, DATABASE, SYNC, FIREBASE, NETWORK, UPDATE, MIGRATION, SECURITY, PERFORMANCE, SYSTEM**.

Status: STARTED, SUCCESS, FAILED, PENDING, RETRY, CANCELLED, SLOW e STALLED. Conectividade: ONLINE, OFFLINE, UNKNOWN. Sincronização: NOT_APPLICABLE, PENDING, SYNCED, FAILED, UNKNOWN. Estar offline é um estado normal, não um erro.

## Como instrumentar uma operação

```kotlin
val trace = logger.startTrace(
    LogCategory.SALE, "SALE_STARTED",
    screen = "CashierScreen", component = "PosViewModel", function = "checkout"
)
val bound = logger.bindOperation(trace, movement.id)
withLogTrace(bound) {
    repository.submit(movement)
}
// Em callbacks ou após reabrir o app, recuperar pelo UUID durável:
val recovered = logger.forOperation(movement.id, LogCategory.SYNC)
logger.info(LogCategory.SYNC, "SYNC_OPERATION_SUCCESS", "Confirmação recebida", recovered)
```

`startTrace` gera UUID aleatório. `TraceElement`/`withLogTrace` propagam o contexto pelas corrotinas; callbacks capturam explicitamente o contexto. `bindOperation` vincula o UUID do comprovante ao rastreamento e o índice local permite continuar após reinício. Operações antigas sem vínculo usam UUID determinístico derivado do ID da operação. A retenção pode remover eventos antigos; o índice de IDs permanece limitado a 20.000 vínculos, sem payloads de clientes.

Cada interação de carrinho agora recebe seu próprio UUID de trace. `metadata.parent_trace_id` aponta para a sessão de venda, cujo trace continua acompanhando a finalização e a confirmação remota. Para reunir várias edições da mesma venda, pesquise esse ID pai no texto/metadata. Botão de adicionar, botão de remover e digitação têm eventos diferentes. `revision` identifica as edições efetivas; informar o mesmo valor ou um valor recusado não inventa uma emissão nova.

`startStep` registra início e disponibiliza `finish`, `fail` e `cancel`. A duração usa relógio monotônico; o watchdog verifica etapas ainda abertas. Padrão: menos de 100 ms normal, 100–500 ms warning e acima de 500 ms lenta, com limite de travamento padrão de 15 s. Chamadas de rede e atualização têm limites maiores. A tela prefere uma etapa interna medida ao selecionar o gargalo, sem somar durações sobrepostas.

## Fluxos instrumentados

- Carrinho: interação, solicitação no ViewModel, consulta ao catálogo em memória, validação, adição/remoção, publicação de estado, total, recebimento pela tela e conclusão da passagem de desenho. `STATEFLOW_EMITTED` confirma a publicação; `CASHIER_SCREEN_STATE_RECEIVED` confirma a observação e `CART_RENDERED` confirma o desenho dos itens/quantidades correspondentes. Não é registrada uma consulta fictícia ao banco para cada toque.
- Em rajadas, StateFlow/Compose podem apresentar apenas a revisão mais recente no próximo quadro. `CART_RENDER_SUPERSEDED` informa uma revisão substituída antes do desenho, sem fingir que cada estado intermediário foi exibido. `CASHIER_LIFECYCLE_CHANGED` ajuda a identificar coletores suspensos pelo ciclo de vida. Os tempos do fluxo do carrinho são acumulados desde a entrada no handler; não devem ser somados como etapas independentes.
- Venda: início, pagamentos, finalização, construção do lote, aceitação local, fila pendente, conclusão para o operador, confirmação/rejeição do servidor. O trace da sessão acompanha a finalização e a sincronização; as edições de carrinho usam traces filhos. Uma nova venda recebe outro trace de sessão.
- Estoque: cadastro, exclusão e entrada, etapas de escrita e confirmação posterior.
- Caixa: abertura, ajustes, fechamento, consulta de pagamentos e estorno. Abertura/fechamento continuam sendo transações online; comprovante local da solicitação não é apresentado como abertura/fechamento confirmado.
- Sincronização: estados reais dos snapshots, commits, comprovantes recuperados e tentativas explícitas. A duração de confirmação remota pode incluir espera offline, indicada em metadata e excluída da seleção de gargalo. Falha de listener não é automaticamente rejeição de venda.
- Autenticação/perfis: login, restauração e verificação de sessão, permissões, gravação de perfis e troca de conta, sem credenciais.
- Atualização: consulta, conferência do arquivo, espera de sincronização e ação de download/instalação. O retorno do instalador não é registrado como prova de que o Android instalou a versão.
- Banco local: abertura/recuperação do journal e falhas de persistência. `MIGRATION` está disponível para futuras migrações locais; não existe migração Room de negócio nesta versão. O script administrativo antigo é externo ao processo Android, portanto não aparece como uma execução local do app.

## Retenção, desempenho e privacidade

TRACE/DEBUG: **7 dias**; INFO: **15 dias**; WARNING: **30 dias**; ERROR/CRITICAL: **90 dias**. O histórico ativo também tem limite de **20.000 registros ou 32 MB**, removendo níveis menores primeiro sob pressão. Compactação usa arquivo temporário e troca atômica; pode haver uso transitório adicional em disco durante a reescrita e os arquivos auxiliares são limitados separadamente. A manutenção ocorre no worker, na abertura e durante o uso.

As chamadas normais apenas enfileiram cópias pequenas. Serialização, mascaramento, stack traces e gravação ficam no worker. As filas são limitadas (2.048 normais e 256 prioritários); ERROR/CRITICAL são priorizados e sincronizados ao disco. O painel informa descartes por excesso de volume e falhas de armazenamento. Nenhum sistema local garante preservar todos os registros com disco cheio, encerramento abrupto ou volume ilimitado. A ferramenta não deve bloquear a venda tentando prometer isso.

A interface recebe invalidações reativas, agrupadas em 500 ms, sem consultas repetidas ao Firebase; filtros de texto têm atraso de 250 ms e a lista carrega páginas de 100 itens. Consultas e exportação são executadas em IO. Erros globais têm gravação de recuperação em melhor esforço com espera limitada a 500 ms; o handler anterior do Android sempre recebe a exceção e mantém o encerramento normal.

Não passe senhas, tokens, cartões, CVV, service accounts ou payloads completos ao logger. O saneamento remove campos sensíveis, Bearer/JWT, chaves privadas, valores opacos extensos e números de telefone/cartão; e-mails são mascarados. Clientes são referenciados por IDs, sem nome/telefone completo. UID do operador e UUID do aparelho permanecem para correlação. JSON exportado ainda contém informações técnicas da loja e deve ser compartilhado conscientemente com quem analisa o problema.

## Arquivos principais

Criados: `core/.../logging/{Logging,LocalAppLogger,LocalLogStore,LogPrivacy,OperationTraceIndex}.kt`; `app/.../diagnostics/{AndroidDiagnostics,DiagnosticCrashHandler}.kt`; `app/.../ui/diagnostics/{DiagnosticsFilters,DiagnosticsViewModel,DiagnosticsScreens}.kt`; `app/.../data/RepositoryDiagnostics.kt`; `app/.../updates/LoggingAppUpdates.kt`; testes de diagnóstico, privacidade, UI, callbacks, crash e atualização.

Alterados: `BebidasApplication.kt`, `MainActivity.kt`, `PosApp.kt`, `SettingsScreen.kt`, `PosViewModel.kt`, `CashierScreen.kt`, `CashControlScreen.kt`, os três repositórios Firestore, `OperationLedger.kt`, arquivos Gradle, README e testes dos ViewModels. Os registros são locais: esta funcionalidade não exige novas regras, índices ou plano Blaze.

## Conferência prática nos aparelhos

Os testes JVM validam armazenamento real em arquivos temporários, reabertura, exceções, mascaramento, retenção, filtros/exportação, volume, watchdog e propagação de trace; os testes de repositório simulam callbacks do SDK. Consulte `VALIDACAO-1.3.0.md` para os resultados executados. Isso não substitui testar no Android:

1. Atualizar sem desinstalar, abrir Ajustes → Diagnóstico com administrador e testar restrição de acesso com operador.
2. Abrir caixa conectado, carregar catálogo, ativar modo avião, registrar uma venda completa e conferir eventos locais/pendentes. Reiniciar o app ainda offline e verificar logs preservados.
3. Reconectar e confirmar SALE_SYNCED/SYNC_OPERATION_SUCCESS da mesma operação e trace_id, sem duplicar estoque ou venda.
4. Abrir/fechar caixa conectado; comparar horários, contexto do operador e confirmação remota. Tentar sem conexão e verificar mensagem e registros coerentes.
5. Exportar JSON pelo seletor Android, copiar detalhes, exercitar filtros e limpar com confirmação.
6. Conferir LIVE e rastreamento, atualização do app e leitura de códigos reais. Um crash Android real deve ser reproduzido apenas em ambiente de teste, sem operações pendentes de clientes.
