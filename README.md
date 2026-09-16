# PDV Hora da Gelada

PDV Android nativo para uma loja de conveniência, com Kotlin, Jetpack Compose Material 3, MVVM, domínio separado, Koin, Navigation Compose, Firebase Authentication e Cloud Firestore com persistência offline.

A versão atual do projeto é **1.3.0 (6)**. Ela acrescenta [Logs e Diagnóstico locais](DIAGNOSTICO.md) e inclui a correção de estabilidade do leitor de código de barras, preservando as funções da 1.2.0. O `applicationId` continua **`br.com.bebidaspdv`** e a chave de assinatura deve ser preservada.

O roteiro atual está em [INSTALAR-1.3.0.md](INSTALAR-1.3.0.md), e os resultados de teste em [VALIDACAO-1.3.0.md](VALIDACAO-1.3.0.md). A versão 1.3.0 usa as mesmas regras e índices da 1.2.0. Preparação do Firebase, distribuição e conferência prática nos dois celulares são etapas separadas. Gerar o instalador não executa a migração antiga, não configura o primeiro administrador e não publica regras ou índices.

## Comece por aqui

- **Atualização atual:** [INSTALAR-1.3.0.md](INSTALAR-1.3.0.md).
- **Investigar problemas e exportar logs:** [DIAGNOSTICO.md](DIAGNOSTICO.md).
- **Código privado no GitHub:** [akylis-doc/pdv-hora-da-gelada](https://github.com/akylis-doc/pdv-hora-da-gelada). Configuração Firebase, chave de assinatura e dados locais são mantidos fora do repositório.
- **Histórico — instalação da 1.2.0:** [INSTALAR-1.2.0.md](INSTALAR-1.2.0.md), com a ordem de preparação e instalação nos dois celulares.
- **Histórico — validação da 1.2.0:** [VALIDACAO-1.2.0.md](VALIDACAO-1.2.0.md), com os resultados daquela versão.
- **Preparação para quem ainda usa uma versão anterior à 1.2.0:** leia [ATUALIZACAO-1.2.0.md](ATUALIZACAO-1.2.0.md). As mudanças de dados e regras descritas ali pertencem à 1.2.0; os logs da 1.3.0 não exigem outra migração.
- **Preparar o histórico existente:** [migração dos dados](tools/admin/MIGRACAO-V3.md).
- **Definir o primeiro administrador:** [configuração de perfis](tools/admin/PERFIS.md). Depois, os operadores podem ser criados pelo próprio aplicativo.
- **Adicionar fotos:** use [fotos-produtos](fotos-produtos/README.md). Os arquivos serão incorporados em uma futura atualização; apenas o identificador da foto fica no Firestore.
- **Distribuição futura:** [ATUALIZACOES.md](ATUALIZACOES.md). O funcionamento do novo botão **Instalar agora** está em [ATUALIZACAO_BAIXADA.md](ATUALIZACAO_BAIXADA.md).

As instruções de versões anteriores continuam disponíveis como histórico. Para a preparação da 1.2.0, siga os documentos desta versão.

## Abrir e configurar o projeto

1. Abra esta pasta no Android Studio com JDK 17. O projeto usa Gradle 8.11.1, Android Gradle Plugin 8.9.3, Kotlin 2.1.21, SDK 35 e Android mínimo 8.0 (API 26).
2. Para uma loja nova, crie um projeto Firebase exclusivo da loja, um banco Cloud Firestore e registre o aplicativo Android `br.com.bebidaspdv`. Para uma loja existente, mantenha o projeto e o banco atuais.
3. Coloque a configuração baixada do Firebase em `app/google-services.json`. Sem esse arquivo, o app abre orientações de configuração, sem catálogo ou backend fictício.
4. Ative **Authentication → E-mail/senha**. A primeira conta do responsável é criada no Firebase Console e recebe o perfil de administrador pelo procedimento em [PERFIS.md](tools/admin/PERFIS.md). Não existe cadastro público com acesso automático à loja.
5. Se houver histórico, prepare-o conforme [MIGRACAO-V3.md](tools/admin/MIGRACAO-V3.md), com os dois aparelhos sincronizados e sem registrar operações. O procedimento conserva os IDs, o estoque e os valores históricos.
6. Na futura preparação da distribuição, publique as regras e os índices revisados no projeto correto e só então instale a mesma versão nova nos dois celulares. O roteiro está em [ATUALIZACAO-1.2.0.md](ATUALIZACAO-1.2.0.md). Nenhuma dessas ações acontece ao abrir o código.
7. Para verificar o código **sem produzir APK**, as tarefas são `:core:test`, `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:testDebugUnitTest` e `:app:lintDebug`. No Windows, use o wrapper `gradlew.bat`; a variante de testes precisa da chave original, inclusive para compilar.
8. Depois da preparação dos dados e da instalação da versão de teste, faça o primeiro acesso conectado nos dois aparelhos. Abra uma sessão em **☰ → Controle de caixa** antes de vender. Cadastre produtos em **Estoque** e registre uma entrada para o saldo inicial.

As versões das bibliotecas estão fixadas nos arquivos de build. `local.properties` pertence à máquina de desenvolvimento. Credenciais administrativas e chaves privadas não devem ir para o projeto, pasta de fotos ou aplicativo.

Para mudar somente o nome exibido, edite `app_name` em `app/src/main/res/values/strings.xml`. Preserve o identificador do aplicativo e a assinatura para permitir atualização sobre a instalação existente.

## Funções da versão 1.2.0

| Área | Comportamento |
| --- | --- |
| Navegação | Apenas **Caixa** e **Estoque** na barra principal. O menu **☰**, no canto superior esquerdo, reúne **Relatórios**, **Fiado**, **Ajustes**, **Controle de caixa**, **Perfis da equipe** e **Exportar dados**, conforme as permissões. |
| Caixa | Busca por nome, código digitado/leitor externo ou câmera; favoritos por operador neste aparelho; fotos no catálogo e carrinho; botões grandes e quantidade pelo teclado; venda por item ou caixa. O carrinho revisa itens, cliente, pagamentos e finalização. |
| Pagamento | Dinheiro, Pix, débito, crédito e fiado; combinação de formas, valor restante e troco somente em dinheiro. Cliente opcional nas vendas comuns e cadastrado para fiado. A mensagem de venda desaparece rapidamente e o acompanhamento da sincronização permanece no topo. |
| Estoque | Categorias para bebidas, alimentos, cigarros e outros; unidade de estoque e quantidade por caixa; custo informado por item ou caixa; preço de venda por item/caixa; fotos locais; avisos internos de estoque baixo ou em falta. Exclusão com confirmação desativa o produto e preserva seu histórico. |
| Clientes e fiado | Telefone visível e pesquisável; edição do cadastro; limite de fiado por cliente, vencimento e indicação de atraso; liberação acima do limite somente pelo administrador, com motivo. Recebimentos parciais, contas em aberto e histórico mensal. |
| Controle de caixa | Abertura com dinheiro inicial, sangrias, suprimentos, dinheiro esperado, valor contado e diferença no fechamento. Cada sessão identifica aparelho e operador. **Ver formas de pagamento** confere vendas recebidas, parcelas de fiado e devoluções em dinheiro, Pix, débito e crédito, sem aumentar o dinheiro físico esperado com Pix ou cartões. |
| Estornos | Estorno completo com conferência, motivo e responsável. Repõe as unidades, registra a devolução por forma de pagamento e cancela apenas o fiado ainda não recebido. A venda original continua no histórico; o mesmo estorno não pode ser aplicado duas vezes. |
| Relatórios | Resumo baseado nos valores recebidos no mês, descontando devoluções, CMV proporcional e despesas registradas. Volume, ranking de produtos, vendas individuais e comparação por operador ficam separados desse resultado financeiro. |
| Perfis | Administrador cadastra contas dentro do app sem sair da própria sessão. Pode editar permissões, promover outro administrador e ativar/desativar operadores. A sessão validada permanece salva para os próximos acessos. |
| Exportação | CSV de vendas do mês, estoque atual, clientes e fiados em aberto. A consulta só ocorre ao solicitar a exportação, exige dados confirmados e respeita a permissão de exportar. |
| Atualização | Download e instalação têm ações separadas. **Instalar agora** reaparece para um arquivo completo e verificado, inclusive após reabrir o aplicativo ou cancelar a instalação. |
| Tema | Claro/escuro conforme o sistema, destaque verde para finalizar e controles preparados para toque. |

### Custo de compra por caixa

Informe a quantidade de itens da caixa e selecione **Caixa** acima do custo. O valor exato pago pela caixa fica salvo. Para uma venda avulsa, o app divide esse custo pelo número de itens e arredonda para centavos com **HALF_UP**; uma venda da caixa inteira preserva o custo exato informado.

Exemplo: caixa com 12 itens por R$ 50,00 resulta em custo avulso de R$ 4,17; o custo da caixa inteira continua R$ 50,00. Por causa do arredondamento em centavos, vender os 12 itens separadamente pode reconhecer alguns centavos a mais ou a menos que vender a caixa fechada. O custo congelado em cada venda não muda quando o cadastro é editado depois.

A caixa e o item avulso usam o mesmo saldo de estoque. Um maço pode ser cadastrado como um item de estoque, com a caixa contendo a quantidade de maços correspondente. As quantidades permanecem inteiras.

### Como ler os relatórios

**Resumo** considera os pagamentos recebidos na venda, mais os recebimentos de fiado ocorridos no mês, menos os valores devolvidos em estornos. A parte ainda fiada só entra quando houver recebimento. O troco já está descontado. O CMV acompanha a mesma proporção recebida usando o custo histórico; pagamentos parciais usam o custo acumulado antes e depois de cada parcela, evitando duplicar centavos.

**Resultado líquido recebido = recebido após estornos − CMV proporcional − despesas do mês.** Água, energia, aluguel e outros custos devem ser registrados. Cartões são considerados na data da venda; o app não concilia automaticamente repasses, taxas ou extratos bancários.

**Volume, Produtos, Vendas e Por conta** mostram as vendas originais do mês, incluindo fiado. O ranking compara unidades de estoque e lucro histórico antes das despesas. Esses números não são o valor disponível em caixa; os estornos têm registros próprios e são abatidos do resumo financeiro. Vendas antigas sem detalhes de pagamento são consideradas recebidas na data da venda, sem inventar distribuição por forma de pagamento.

## Dados, segurança e atomicidade

O módulo `core` não depende de Android ou Firebase. Valores são calculados em centavos inteiros; os campos monetários `Double` das versões anteriores continuam legíveis. Produtos, vendas, clientes e comprovantes novos usam UUID, sem auto incremento.

| Coleção | Finalidade |
| --- | --- |
| `Produtos/{UUID}` | Catálogo, saldo, categoria, unidade, custo, preços, embalagem e identificador da foto. |
| `Vendas/{UUID}` | Venda ou entrada, itens com custo/preço congelados, data/hora, operador, cliente, pagamentos e vínculo com a sessão de caixa. |
| `Clientes/{UUID}` | Nome, telefone, limite de fiado e saldo agregado a receber. |
| `ContasReceber/{UUID da venda}` | Valor original, recebido por forma, cancelado, vencimento e situação aberta/encerrada. |
| `Recebimentos/{UUID}` | Comprovante imutável de cada baixa, operador, sessão e CMV reconhecido na parcela. |
| `Despesas/{UUID}` | Data, categoria, descrição, valor e operador. |
| `Perfis/{UID do Authentication}` | Nome, situação e permissões da conta. O UID preserva a ligação com o histórico. |
| `CaixaSessoes` / `CaixasDispositivos` | Sessões por aparelho/operador e vínculo com a sessão ativa. |
| `MovimentosCaixa` / `Estornos` | Movimentos do caixa e comprovantes de estorno, preservando a venda original. |

Uma venda ou entrada usa **um único WriteBatch** com seu comprovante e `FieldValue.increment()` no estoque. Vendas novas também atualizam a sessão; fiados criam a conta a receber e atualizam o saldo do cliente no mesmo lote. Lotes funcionam offline. A interface libera o próximo atendimento depois da gravação local e acompanha a confirmação do servidor separadamente.

Recebimento de fiado, abertura, ajuste e fechamento do caixa precisam de internet. O fechamento confere a revisão do saldo e não aceita uma contagem baseada em operações que mudaram enquanto a tela estava aberta. Estornos conferem os dados no servidor e vinculam atomicamente estoque, devolução, sessão e cancelamento do saldo. Operações com resposta incerta conservam o identificador para conferência; não devem ser refeitas com outro ID.

As regras validam autorização, vínculo dos comprovantes, valores e alterações permitidas. Um perfil desativado prevalece sobre a antiga autorização `pdv`. A autorização legada continua sendo tratada para contas existentes, mas não cria administradores. As permissões restringem ações e telas; o catálogo necessário para vender offline contém custos, portanto a permissão de relatórios não representa sigilo desses campos no aparelho.

## Consultas e uso offline

- Relatórios passam a consultar o mês selecionado somente enquanto a tela está aberta. Sair dela interrompe suas assinaturas. O menu lateral organiza a navegação; a economia de consultas vem desse controle no código.
- Fiado prioriza contas em aberto e consulta recebimentos por mês. Não há carregamento automático de todo o histórico de vendas e recebimentos ao iniciar o app. Catálogo, clientes, sessão ativa e acompanhamento essencial de pendências continuam disponíveis para a operação.
- Abrir relatórios ou exportar ainda consome leituras. Consultas mensais e de contas abertas carregam os documentos correspondentes; não há garantia de permanecer dentro da cota gratuita em qualquer volume de uso. As fotos não consomem armazenamento de imagens no Firestore e não usam Firebase Storage/Blaze.
- O primeiro acesso e a abertura da sessão exigem internet. Com sessão aberta e catálogo já sincronizado, é possível vender offline. Recebimentos, estornos, ajustes e fechamento esperam a reconexão.
- Dois celulares offline podem vender a última unidade simultaneamente. Os incrementos são preservados e o saldo final pode ficar negativo, sinalizado para conferência. Da mesma forma, uma venda fiada aceita localmente pode ser recusada na sincronização se ultrapassar o limite atualizado no servidor.
- Dados em cache ou pendentes são identificados. Um relatório offline pode estar incompleto. A confirmação local não é a confirmação do servidor; confira os comprovantes recusados antes de registrar novamente.
- Mantenha data e hora automáticas. O mês usa `America/Sao_Paulo`, com início inclusivo e mês seguinte exclusivo. O custo congelado é o último conhecido pelo aparelho no momento da finalização.

Não desinstale nem limpe os dados do aplicativo com gravações pendentes. O carrinho ainda não finalizado fica em memória. A persistência pertence ao armazenamento privado do aplicativo, e os backups Android estão desativados para evitar restaurar o cache de outro aparelho.

## Limites e conferência

Cada movimentação aceita **até 8 produtos distintos**, com quantidade de 1 a 9.999 por linha e preços/totais limitados a 99.999.999 centavos. O limite de linhas mantém a validação atômica dentro do orçamento das regras. Lucro negativo é permitido quando a venda fica abaixo do custo.

O estorno é completo; não há devolução parcial de itens, emissão fiscal, integração bancária ou com maquininha. Fotos são escolhidas do catálogo incorporado: a câmera desta versão lê códigos de barras, não envia fotos de produtos. A integração com WhatsApp continua sendo uma etapa futura.

A preparação e os cenários de conferência da versão estão em [ATUALIZACAO-1.2.0.md](ATUALIZACAO-1.2.0.md). Os testes em `tests/firestore` usam emulador, sem publicar regras em um projeto real. Os registros de versões anteriores não comprovam a validação desta atualização. Câmera, instalação, navegação no primeiro acesso e uso simultâneo precisam de conferência posterior nos dois celulares.

## Referências oficiais

- [Firebase: transações e lotes](https://firebase.google.com/docs/firestore/manage-data/transactions)
- [Firebase: cache e dados offline](https://firebase.google.com/docs/firestore/manage-data/enable-offline)
- [Firebase: regras de operações atômicas](https://firebase.google.com/docs/firestore/security/rules-conditions)
- [Firebase: configuração Android](https://firebase.google.com/docs/android/setup)
- [Android: ListenableFuture usado pelo CameraX](https://developer.android.com/develop/background-work/background-tasks/asynchronous/listenablefuture)
