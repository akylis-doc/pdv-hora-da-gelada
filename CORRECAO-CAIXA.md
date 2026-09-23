# Correção da atualização visual do Caixa

Esta correção foi incorporada à atualização **1.3.1 (7)**. O APK 1.3.0 anterior permanece preservado como histórico. Veja [INSTALAR-1.3.1.md](INSTALAR-1.3.1.md) e [VALIDACAO-1.3.1.md](VALIDACAO-1.3.1.md) para a instalação e a conferência do novo pacote. As medições abaixo pertencem à investigação da correção; a variante temporária sem depuração serviu somente à comparação no emulador isolado.

**Situação da validação em 22/09:** as duas causas de perda de atualização foram reproduzidas e corrigidas, e os testes funcionais passaram. A comparação adicional de desenho passou em retrato conectado e em modo avião, mas o primeiro desenho do painel lateral em debug ainda excedeu 500 ms. A aprovação de desempenho em todos os cenários permanece parcial; os celulares físicos não foram testados.

## Causa raiz e reprodução

O carrinho já era uma `MutableStateFlow<List<CartLine>>` privada do `PosViewModel`, exposta com `asStateFlow()`. As alterações substituíam a lista com `map`, `filterNot` ou concatenação; não havia mutação in-place. A quantidade e o total da tela eram derivados dessa lista. O `rememberSaveable` do produto guardava apenas a escolha de unidade/caixa, não uma cópia independente do carrinho.

O ponto de interrupção era o ciclo de vida da entrada de navegação. `PosApp.go` usa `launchSingleTop = true`. Com Navigation Compose 2.9.0, selecionar novamente Caixa podia deixar sua entrada em `CREATED`. `collectAsStateWithLifecycle()` exige pelo menos `STARTED`: o ViewModel mudava, mas a tela parava de receber as emissões. Navegar para outra seção e retornar reativava a observação.

Antes de alterar a implementação, uma prova com `CashierScreen` e `PosViewModel` reais reproduziu a falha: **3 testes passaram e 2 falharam**. Os dois casos que selecionavam novamente Caixa apresentaram quantidade 1/10 no modelo, campo visual vazio e lifecycle `CREATED`. Isso aconteceu tanto pelo botão quanto pelo campo de quantidade, com catálogo em memória.

A [nota oficial do Navigation 2.9.1](https://developer.android.com/jetpack/androidx/releases/navigation#2.9.1) confirma a correção de entradas `singleTop` que nunca avançavam além de `CREATED`. Foi aplicada essa atualização pontual, mantendo a coleta vinculada ao ciclo de vida.

Havia também um problema independente nos botões: ambos chamavam alteração absoluta usando a quantidade capturada pela composição anterior. Uma prova anterior à correção chamou o mesmo callback 20 vezes antes da próxima composição e terminou com quantidade 1. Agora o incremento/decremento é calculado a partir do carrinho atual no ViewModel.

## Fluxo e gerenciamento de estado

```text
Adicionar/remover no ProductRow
  → addFromUi / removeFromUi
  → quantidade atual do PosViewModel ± 1
  → validação local e nova lista em _cart
  → StateFlow
  → collectAsStateWithLifecycle na CashierScreen
  → ProductRow + total + CartPanel
  → passagem de desenho

Digitar no ProductRow
  → setQuantityFromUi
  → validação local da quantidade absoluta
  → mesmo fluxo de estado e desenho
```

`_cart` continua sendo a única fonte editável do carrinho. `CartPanel` recebe a lista já observada pela tela, tanto no painel lateral quanto na revisão em retrato. `CartRender` é apenas um recibo imutável para correlacionar uma revisão com o desenho, não uma segunda fonte de dados de negócio.

Não foram adicionados atrasos, tentativas artificiais ou comandos para forçar recomposição. O layout, estoque, regras de negócio, Firestore, persistência e sincronização não foram alterados. Adicionar e editar quantidades continuam sendo operações locais.

## Instrumentação mantida

Cada interação recebe um novo UUID de `trace_id`. `metadata.parent_trace_id` relaciona as edições à sessão de venda. Cada alteração efetiva incrementa `revision`; uma quantidade igual à atual ou recusada não simula uma emissão.

| Fluxo | Eventos principais |
| --- | --- |
| Adicionar | `ADD_TO_CART_BUTTON_CLICK`, `ADD_TO_CART_STARTED`, `ADD_TO_CART_STATE_CHANGED`, `ADD_TO_CART_COMPLETED` |
| Digitar | `QUANTITY_INPUT_CHANGED`, `SET_PRODUCT_QUANTITY_REQUEST`, `SET_QUANTITY_STATE_CHANGED`, `SET_PRODUCT_QUANTITY_COMPLETED` |
| Remover | `REMOVE_FROM_CART_BUTTON_CLICK`, `REMOVE_FROM_CART_STARTED`, `REMOVE_FROM_CART_STATE_CHANGED`, `REMOVE_FROM_CART_COMPLETED` |
| Publicação | `STATEFLOW_EMITTED`, com `stage=publisher` |
| Observação | `CASHIER_SCREEN_STATE_RECEIVED`, com lifecycle e revisão |
| Desenho | `CART_RENDER_STARTED`, `CART_RENDERED`, com `stage=draw_completed` |
| Ciclo de vida | `CASHIER_LIFECYCLE_CHANGED` |
| Revisão substituída | `CART_RENDER_SUPERSEDED` |

São registrados produto, quantidade solicitada, quantidade anteriormente mostrada, quantidade antes/depois no ViewModel, quantidade observada pela UI, revisão e total. A confirmação de desenho só é aceita quando os IDs, quantidades e embalagens do snapshot correspondem ao recibo.

Em uma rajada, Compose/StateFlow podem apresentar diretamente a última revisão no próximo quadro. As intermediárias recebem `CART_RENDER_SUPERSEDED`; não são declaradas como desenhadas. `STATEFLOW_EMITTED` indica publicação, e não recebimento por todos os coletores.

Os tempos do carrinho são **acumulados desde a entrada no handler**, medidos com relógio monotônico. Não some esses valores como se fossem durações independentes. `CART_RENDERED` indica conclusão da passagem de desenho do Compose, não uma medição física dos pixels no visor.

Para investigar uma ação: abra Ajustes → Diagnóstico → Logs, procure seu `trace_id` e abra **Ver rastreamento**. Compare a mesma revisão em publicação, recebimento e desenho. Para reunir ações da mesma venda, pesquise o ID de `parent_trace_id`. A exportação JSON continua disponível nessa tela.

## Arquivos desta correção

- `app/build.gradle.kts`: Navigation 2.9.1 e dependências dos testes Android.
- `app/src/main/java/br/com/bebidaspdv/ui/PosViewModel.kt`: incremento/decremento pelo estado atual, traces por ação e recibos de revisão.
- `app/src/main/java/br/com/bebidaspdv/ui/CashierScreen.kt`: callbacks separados, lista compartilhada com o painel e instrumentação de observação/desenho.
- `app/src/test/java/br/com/bebidaspdv/ui/PosViewModelTest.kt`: regressões de estado, traces, estoque, embalagem e reconhecimento de desenho.
- `app/src/androidTest/java/br/com/bebidaspdv/ui/CashierScreenTest.kt` (novo): testes da tela real e navegação real, com repositório isolado.
- `app/src/androidTest/java/br/com/bebidaspdv/ui/CashierNaturalFrameTest.kt` (novo): toque e alteração do campo pela interface, com os quadros normais do Android e limite de desempenho de 500 ms.
- `DIAGNOSTICO.md`, `README.md` e este documento: descrição da correção e interpretação dos registros.

## Validação

Os testes Android usam a tela, o ViewModel e o NavHost reais em Android 15/API 35. O catálogo e os callbacks de sincronização são controlados pelo teste; não há gravações de negócio no Firebase. O logger do teste recolhe os eventos em memória para medir o percurso até o desenho. Portanto, estes números não medem o tempo de persistência do journal nem constituem um teste de sincronização contra o servidor da loja.

Testes unitários: **217 aprovados**, sendo 97 do core e 120 do app, incluindo 12 de `PosViewModelTest`. Compilação debug/release e Lint aprovados: **0 erros e 12 avisos**.

### Medição com quadros normais do Android

Foi acrescentado um teste com `ActivityScenario`, toque injetado na posição do botão e alteração do campo acessível. Ele usa a própria `CashierScreen`, o `PosViewModel` e a navegação `singleTop`, sem instalar `ComposeTestRule` nem avançar um relógio artificial. A quantidade é conferida na árvore de acessibilidade, além do recibo de desenho. O limite de **500 ms** continua obrigatório nesse teste.

| Layout e condição | Ação | Publicação | Recebimento pela tela | Desenho concluído | Resultado |
| --- | --- | ---: | ---: | ---: | --- |
| Retrato, 411 dp, modo avião | Adicionar primeira unidade | 1 ms | 80 ms | **138 ms** | Aprovado |
| Retrato, 411 dp, modo avião | Digitar 10 unidades | 1 ms | 56 ms | **94 ms** | Aprovado |
| Retrato, 411 dp, conectado | Adicionar primeira unidade | 24 ms | 551 ms | **703 ms** | Conteúdo correto; desempenho reprovado |
| Retrato, 411 dp, conectado | Digitar 10 unidades | 2 ms | 90 ms | **128 ms** | Aprovado |
| Painel lateral, 1200 dp, modo avião | Adicionar primeira unidade | 3 ms | 384 ms | **1.218 ms** | Conteúdo correto; desempenho reprovado |
| Painel lateral, 1200 dp, modo avião | Digitar 10 unidades | 1 ms | 163 ms | **251 ms** | Aprovado |

O teste completo de retrato em modo avião passou. As execuções de retrato conectado e painel lateral terminaram com **uma falha cada**, no tempo do primeiro desenho; a digitação e os valores visíveis no catálogo/painel foram conferidos. A suspensão em `CREATED` não voltou a ocorrer. A conectividade foi alterada no emulador, mas o repositório de catálogo permanece local e isolado em todos os casos; essas amostras não demonstram que a rede causou a diferença.

Após separar a medição de velocidade, o teste funcional completo do painel lateral também foi executado novamente: **1/1 aprovado**, conferindo o botão, a digitação e a linha de revisão. Isso não muda a reprovação de desempenho acima. As medições usam a variante debug em emulador Android 15, não os aparelhos físicos da loja.

### Comparação adicional de configuração em 22/09

Foi criada apenas no ambiente de investigação uma variante `cartPerformance`, herdando código, dependências, fotos e assinatura de debug, mas com `debuggable=false`. O script de inicialização está em `work/cart-probe/performance.gradle`, fora do projeto do aplicativo. Não houve alteração permanente de `buildTypes`, de layout ou do atualizador. A variante não usa R8/minificação e não representa uma nova configuração oficial de distribuição.

As duas primeiras tentativas não chegaram a interagir com o carrinho. Uma janela do sistema **“System UI isn't responding”**, confirmada por `dumpsys window` e pela árvore de acessibilidade, cobria a tela. Os testes falharam ao procurar o primeiro botão, sem nenhum evento de edição. A evidência foi preservada em `nondebug-systemui-anr.xml`; a janela foi dispensada somente no emulador. Isso explica essas falhas de preparação, não as medições de desenho anteriores.

O Android preparou antecipadamente os pacotes do app e do teste com o mesmo modo de compilação (`speed`) nas duas variantes. Após liberar a janela, a comparação usou o mesmo emulador Android 15, aceleração Intel, código, catálogo isolado e limite de 500 ms. A conferência inclui quantidade visível, trace/revisão e desenho; os testes não instalam relógio artificial.

| Variante / layout / condição | Ação | Publicação | Recebimento | Desenho | Resultado |
| --- | --- | ---: | ---: | ---: | --- |
| Sem depuração / retrato / modo avião | Adicionar | 2 ms | 41 ms | **63 ms** | Aprovado |
| Sem depuração / retrato / modo avião | Digitar 10 | 1 ms | 34 ms | **52 ms** | Aprovado |
| Sem depuração / painel lateral / modo avião | Adicionar | 1 ms | 48 ms | **277 ms** | Aprovado |
| Sem depuração / painel lateral / modo avião | Digitar 10 | 0 ms | 70 ms | **119 ms** | Aprovado |
| Debug / painel lateral / modo avião | Adicionar | 3 ms | 176 ms | **979 ms** | Conteúdo correto; desempenho reprovado |
| Debug / painel lateral / modo avião | Digitar 10 | 3 ms | 115 ms | **183 ms** | Aprovado |
| Debug / retrato / modo avião | Adicionar | 10 ms | 173 ms | **255 ms** | Aprovado |
| Debug / retrato / modo avião | Digitar 10 | 1 ms | 127 ms | **198 ms** | Aprovado |
| Debug / retrato / conectado | Adicionar | 3 ms | 252 ms | **378 ms** | Aprovado |
| Debug / retrato / conectado | Digitar 10 | 7 ms | 187 ms | **296 ms** | Aprovado |

Foram cinco execuções completas do mesmo cenário: quatro aprovadas e uma reprovada por desempenho na primeira adição do painel lateral em debug. Todos os valores visíveis foram conferidos, inclusive após a medição lenta. O estado de navegação chegou a `RESUMED`; não houve suspensão em `CREATED` nem perda de incremento.

Os resultados são compatíveis com custo adicional de depuração, especialmente ao montar o painel pela primeira vez. A [documentação de desempenho do Compose](https://developer.android.com/develop/ui/compose/performance) orienta medir em release com R8 porque debug adiciona custo. Esta experiência separou a flag de depuração sem introduzir R8, mas as execuções foram sequenciais, com pressão de memória no host; não permitem atribuir todo o atraso a uma única configuração nem garantir tempos em aparelhos físicos. Não se justifica refatorar o painel ou alterar o backend a partir destas amostras.

Evidências locais: `nondebug-portrait-offline-unobstructed`, `nondebug-wide-offline`, `debug-wide-offline-comparison`, `debug-portrait-offline-comparison` e `debug-portrait-online-comparison`, cada uma com resultado `.txt` e eventos `.json` em `work/cart-probe`. O histórico de falhas abaixo continua válido; não foi apagado nem substituído pelos melhores tempos.

### Diferença entre teste funcional e teste de desempenho

A investigação temporária de stacks identificou o avanço de `MainTestClock`/Espresso em 53 das 71 amostras do primeiro desenho do teste anterior. A [documentação oficial de sincronização](https://developer.android.com/develop/ui/compose/testing/synchronization) explica que as ações/assertions podem avançar recomposições e animações até o estado ocioso, enquanto o desenho Android possui seu próprio ciclo. Isso altera a interpretação do intervalo medido dentro de `ComposeTestRule`.

Por isso, `CashierScreenTest` mantém as verificações funcionais de quantidade visível, navegação, total e recibo de desenho; a barreira de 500 ms fica em `CashierNaturalFrameTest`, que não controla o relógio da UI. O limite não foi aumentado. O teste com quadros naturais também encontrou a lentidão inicial no painel lateral, portanto o relógio artificial não explica sozinho a ocorrência. O sampler temporário foi removido e não integra o aplicativo nem os testes entregues.

### Histórico das medições com o relógio de teste

Suíte curta conectada: **10/10 aprovados**. Suíte curta em modo avião: **10/10 aprovados**. A execução posterior em modo avião, incluindo permanência de dois minutos, aprovou **11/11** (dez cenários repetidos mais o cenário prolongado). Não são 31 casos diferentes: são execuções dos mesmos cenários em condições distintas.

Medições acumuladas das duas suítes curtas aprovadas, com 26 confirmações de desenho em cada uma:

| Estado / etapa | Mediana | Percentil 95 | Máximo |
| --- | ---: | ---: | ---: |
| Conectado / publicação | 0 ms | 8 ms | 10 ms |
| Conectado / recebimento pela tela | 28 ms | 53 ms | 55 ms |
| Conectado / desenho concluído | 78 ms | 223 ms | 288 ms |
| Modo avião / publicação | 0 ms | 6 ms | 14 ms |
| Modo avião / recebimento pela tela | 27 ms | 126 ms | 139 ms |
| Modo avião / desenho concluído | 97 ms | 234 ms | 250 ms |

Exemplo real de adição após selecionar Caixa novamente: `trace_id=14868e92-b7ee-4632-a8d7-b8f31a801212`, revisão 1. Clique **0 ms** → estado alterado/publicado **0 ms** → tela recebe com lifecycle `RESUMED` **21 ms** → desenho **71 ms**. O teste conferiu quantidade visível **1**. A prova anterior com Navigation 2.9.0 falhava nesse mesmo caminho.

No cenário prolongado aprovado, o primeiro desenho levou **327 ms**; após dois minutos, a adição levou **185 ms** e a digitação de quantidade 12 levou **44 ms**. Nessa execução anterior à separação dos tipos de teste, os pontos verificados também passaram pelo limite de 500 ms.

O painel lateral foi exercitado em 1200×800/160 dpi. A execução isolada com aceleração Intel também reprovou o limite de 500 ms no primeiro desenho: **1.696 ms**. O lifecycle estava `RESUMED`; a publicação ocorreu em **9 ms**, o recebimento em **141 ms** e o restante concentrou-se até o desenho. Logo, atualizar a biblioteca corrige a suspensão do coletor, mas não comprova desempenho imperceptível em toda primeira abertura do painel nesse ambiente.

A execução completa do painel lateral, com `ErrorCollector` e aceleração Intel, confirmou visualmente **1 unidade após adicionar** e **10 unidades após digitar**, tanto no catálogo quanto na linha de revisão. A primeira atualização levou **1.541 ms** (publicação 3 ms, recebimento 154 ms); a digitação seguinte levou **316 ms** (publicação 18 ms, recebimento 211 ms). O teste terminou **reprovado por desempenho**, com uma única falha, preservando o limite original. Não houve falha de conteúdo/quantidade nem perda da observação do estado.

**Conclusão da validação:** a regressão de lifecycle foi reproduzida antes e deixou de ocorrer com a correção; os cenários funcionais de retrato, rajadas, navegação, estoque/total e uso prolongado passaram. A comparação de 22/09 aprovou retrato conectado e offline em debug, além de retrato e painel lateral sem depuração. A exigência de resposta inferior a 500 ms em todas as primeiras interações **não está integralmente aprovada**: permanecem as amostras anteriores de 703 ms e 1.218 ms, e houve 979 ms no painel lateral em debug na comparação adicional. O custo se concentra depois da publicação, na primeira composição/layout/desenho. Há evidência de influência da configuração de depuração e do ambiente; não foi encontrada uma correção adicional mínima no código da tela que garanta eliminar esse custo em todas as condições. Não se deve considerar esta entrega uma homologação de desempenho nos dois celulares físicos.

As suítes curtas usaram renderização SwiftShader por software: conectado em 1080×1920/420 dpi e modo avião em 720×1280/280 dpi, preservando aproximadamente a mesma largura em dp. Houve preparação antecipada dos pacotes no segundo ambiente. Os percentis descrevem essas amostras, não uma comparação controlada entre velocidade online e offline.

Na retomada de 21/09, duas tentativas foram interrompidas durante a inicialização do processo, antes dos testes: os registros Android indicaram ANR no carregamento do runtime/instrumentação e na inicialização de componentes. Houve pressão de memória e CPU no emulador/host. Após preparar os pacotes com a compilação antecipada do Android, a suíte curta em modo avião executou os dez casos e passou. Isso foi uma preparação do ambiente de teste, sem mudanças no aplicativo.

Também foram preservadas duas reprovações do limite de desempenho no cenário prolongado executado isoladamente: o primeiro desenho levou **1.069 ms conectado** e **616 ms em modo avião**, antes de iniciar a espera de dois minutos. A tela recebeu o estado e estava `RESUMED`, portanto não foi a suspensão em `CREATED` da falha original. Esses resultados impedem prometer menos de 500 ms em toda primeira interação sob carga. O limite do teste não foi aumentado. Os resultados da suíte e do uso continuado devem ser interpretados junto com essas ocorrências, não como garantia para todos os aparelhos.

A primeira execução isolada do painel lateral, em 1200×800/160 dpi com SwiftShader, também reprovou o limite: publicação **4 ms**, recebimento **329 ms**, desenho **2.100 ms**. Essa amostra permanece registrada separadamente da verificação com aceleração gráfica. 

| Cenários solicitados | Cobertura automatizada |
| --- | --- |
| 1, 4 e 8: adicionar, aumentar quantidade, um item | Botão em tela isolada, em NavHost e após selecionar Caixa novamente; quantidade exibida e confirmação de desenho |
| 2 e 9: vários produtos e carrinho cheio | Oito produtos distintos, limite atual da aplicação, total visível e abertura da revisão |
| 3: muitos cliques rápidos | Vinte chamadas do mesmo callback antes da próxima composição; depois cinco remoções; resultado 20 e depois 15, sem duplicações |
| 5, 6 e 7: digitar, reduzir e remover | Digitação de 10, redução para 3, rejeição de 501 acima do estoque 500, remoção por quantidade zero e botão Revisar desabilitado |
| 10: catálogo grande | 5.000 produtos, busca e inclusão de oito produtos diferentes |
| 11 e 12: online e modo avião | Mesma suíte curta em ambas as condições; catálogo fornecido pelo repositório de teste |
| 13 e 16: navegar, voltar, fechar e reabrir a tela | Navegação para Estoque, retorno, remoção/recriação da entrada Caixa preservando o mesmo ViewModel |
| 14: tela aberta por alguns minutos | Cenário específico de dois minutos antes de adicionar e digitar novamente |
| 15: sincronização em paralelo | Snapshots e estados pendente/confirmado simulados enquanto produtos são adicionados |
| 17: total | Valores visíveis conferidos em centavos após adições e alterações |
| 18: estoque | Edição de carrinho não grava no repositório nem altera o saldo; teste unitário adicional de caixa/unidade respeitando estoque compartilhado |
| 19: não duplicar | IDs distintos e uma única linha por produto, inclusive em rajadas |

Também há um cenário de painel lateral com largura de pelo menos 840 dp para conferir quantidade e total compartilhados com a lista de produtos. Reabrir a tela nesta suíte não significa recuperar um carrinho após encerrar o processo: a persistência do rascunho não foi alterada.

### Executar novamente

Use um emulador/aparelho de testes, com a configuração local do projeto e SDK disponíveis:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:compileReleaseKotlin
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=androidx.test.filters.LargeTest
```

Execute a segunda tarefa com rede disponível e depois com o aparelho de testes em modo avião. O teste `screenStillRespondsAfterTwoMinutesOpen` é `LargeTest` e deve ser executado separadamente, mantendo a tela ligada por mais de dois minutos. `wideCartPanelUsesTheSameCollectedQuantity` requer largura de pelo menos 840 dp; em tela menor ele é ignorado por condição explícita. Não conte esse caso como aprovado se houver sido ignorado.

Os testes funcionais salvam os eventos em `Android/data/br.com.bebidaspdv/files/cart-ui-tests`, com o nome do cenário e sufixo `online`/`offline`. O teste de desempenho salva `Android/data/br.com.bebidaspdv/files/natural-cart-<largura>dp.json`; copie o arquivo após cada condição de rede, pois uma execução na mesma largura o substitui. A consulta de lifecycle, o conteúdo visível e o recibo de desenho evitam aprovar somente o estado interno.

Em `CashierNaturalFrameTest`, as violações do limite de 500 ms são recolhidas por `ErrorCollector`: o teste continua para conferir as próximas interações e termina reprovado se qualquer medição exceder o limite. Quantidade incorreta ou confirmação de desenho ausente continuam interrompendo o cenário imediatamente. Isso evita deixar a digitação sem teste por causa de uma primeira atualização lenta, sem esconder a reprovação de desempenho.

## Riscos e limites

A mudança na biblioteca pode afetar outras rotas que usam `singleTop`; esta validação cobre Caixa, saída para Estoque e retorno. Os dois celulares físicos da loja ainda precisam de conferência após instalar uma versão que contenha a correção. Os testes de sincronização paralela simulam callbacks do SDK e não substituem a validação de reconexão real entre dois aparelhos.

Os limites existentes continuam iguais: no máximo oito produtos distintos por venda, até 9.999 unidades por linha e validação contra o estoque local. Não houve migração de dados, alteração das regras Firebase nem refatoração de outros módulos.
