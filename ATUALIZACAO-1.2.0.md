# Atualização 1.2.0 — PDV Hora da Gelada

Este documento orienta a preparação da versão **1.2.0 (5)** e a conferência posterior nos dois celulares. O projeto conserva o identificador `br.com.bebidaspdv`, a assinatura esperada e os registros existentes.

**O [APK de testes 1.2.0](../PDV-Hora-da-Gelada-1.2.0.apk) foi gerado em 11/09/2026**, após autorização, com versão, nome e assinatura conferidos. A validação inicial do código foi concluída antes dessa autorização, sem gerar instaladores. Gerar o APK não distribui a versão nem altera o Firebase. Os procedimentos que alteram o banco, descritos abaixo, continuam como etapas separadas de preparação da instalação. O roteiro breve, incluindo a criação manual dos índices do Controle de caixa, está em [INSTALAR-1.2.0.md](INSTALAR-1.2.0.md).

## O que foi acrescentado

| Uso | Mudança |
| --- | --- |
| Entrar e navegar | Barra principal com apenas Caixa e Estoque; menu ☰ para gestão. O caminho entre Estoque e Caixa foi corrigido para não depender de fechar o aplicativo depois do primeiro cadastro. |
| Cadastrar produto | Categoria para bebidas, alimentos, cigarros ou outros; unidade de estoque; custo por item ou caixa; número de itens por caixa; preços e alertas. |
| Atender no caixa | Favoritos por operador neste aparelho, leitura de código pela câmera, quantidades por teclado ou botões e fotos também no carrinho. A confirmação curta libera a sequência de atendimentos. |
| Cadastrar cliente | Telefone visível e pesquisável, edição de nome/telefone e limite de fiado definido pelo administrador. |
| Vender fiado | Vencimento, alerta de atraso e conferência de limite. Liberação acima do limite exige administrador e motivo. |
| Receber fiado | Pagamento parcial com confirmação conectada; parcela entra no resumo do mês do recebimento, com CMV proporcional. |
| Controlar o dinheiro | Abrir sessão por aparelho e operador, informar troco inicial, registrar sangria/suprimento e comparar dinheiro contado com esperado no fechamento. **Ver formas de pagamento** detalha vendas recebidas, parcelas de fiado e devoluções por dinheiro, Pix, débito e crédito. |
| Corrigir venda finalizada | Estorno completo com motivo, devolução, reposição de estoque e cancelamento do fiado ainda aberto. A venda original permanece registrada. |
| Consultar relatórios | Resumo baseado em recebimentos, separado das vendas originais, rankings e volume. Consultas mensais sob demanda. |
| Gerenciar a equipe | Administrador cria, edita, ativa/desativa perfis dentro do app, sem sair da própria conta. Permissões individuais para ações de gestão. |
| Guardar informações | Exportação CSV de vendas do mês, estoque atual, clientes e fiado em aberto, para o local escolhido no aparelho. |
| Atualizar o aplicativo | Depois do download aparece Instalar agora. O arquivo verificado permanece disponível se a instalação for cancelada ou o app reabrir. |

## Como fica a operação diária

1. Entre com a conta do operador. A sessão continua salva, como antes.
2. Com internet, abra **☰ → Controle de caixa**, informe o dinheiro inicial e toque em **Abrir caixa**. Cada aparelho tem sua própria sessão.
3. Use **Caixa** e **Estoque** na barra principal. As demais áreas estão no menu **☰**, conforme as permissões.
4. No Caixa, procure o produto, use o leitor de código ou os favoritos. Informe a quantidade pelo teclado ou pelos botões e revise o carrinho antes de finalizar.
5. Ao finalizar, a confirmação breve desaparece sozinha. O indicador no topo permite conferir o que já foi confirmado e o que ainda está pendente, sem manter a mensagem sobre o atendimento seguinte.
6. Para receber fiado, fazer sangria/suprimento, estornar ou fechar caixa, reconecte o aparelho. Confira pendências antes de repetir uma operação cuja resposta não chegou.
7. Ao encerrar o turno, sincronize e abra **Controle de caixa → Conferir e fechar caixa**. Informe o dinheiro contado e confira a diferença. Pix e cartões não entram no valor físico esperado.

Com sessão aberta e catálogo conhecido, as vendas continuam funcionando offline. Abrir ou fechar a sessão, alterar o dinheiro do caixa, receber fiado e estornar exigem internet. Isso permite conferir saldo e concorrência antes da confirmação dessas operações.

Em **Controle de caixa → Ver formas de pagamento**, confira uma sessão aberta ou um fechamento deste aparelho. A consulta completa exige internet e acontece somente ao abrir a conferência ou tocar em **Atualizar**. Sincronize as pendências antes; uma nova operação durante a consulta exige atualizar os valores. Dinheiro aparece após o troco, parcelas de fiado entram na sessão em que foram recebidas e devoluções entram na sessão do estorno. **Fiado concedido** aparece separado e não representa recebimento nem saldo devedor atual. O total desta conferência não inclui dinheiro inicial, sangrias ou suprimentos.

## Custo por caixa e outros tipos de produto

No cadastro, escolha a categoria e a unidade usada para contar o estoque. Informe quantos itens há na caixa e selecione como o custo foi informado, acima do campo de custo.

Se uma caixa contém 12 itens e custa R$ 50,00, informe esses dados e selecione custo por caixa. O aplicativo guarda R$ 50,00 para a caixa e calcula R$ 4,17 por item avulso. O arredondamento é HALF_UP em centavos: frações de meio centavo ou mais arredondam para cima. A venda da caixa inteira usa o custo exato da caixa; itens avulsos usam o custo unitário arredondado.

O preço de venda por caixa pode ser diferente da soma dos preços avulsos. A quantidade de estoque, porém, é compartilhada: vender uma caixa de 12 baixa 12 itens. Para cigarros vendidos por maço, por exemplo, cadastre o maço como a unidade de estoque e informe quantos maços existem na caixa.

Alterar custo ou preço vale para novas operações. As vendas anteriores guardam o custo histórico que foi copiado na finalização. Para uma nova compra com custo diferente, edite o cadastro antes de registrar a entrada.

## Relatórios: diferença entre receber e vender

O **Resumo** usa os seguintes cards:

- **Recebido no mês:** pagamentos nas vendas, mais parcelas de fiado recebidas no mês, menos devoluções de estornos. O troco já foi descontado.
- **Custos • CMV:** custo histórico proporcional à parte recebida. No estorno, o custo antes reconhecido também é ajustado.
- **Resultado líquido recebido:** recebido após estornos, menos CMV proporcional e despesas cadastradas no mês.

Exemplo: uma venda de R$ 100,00 tem custo histórico de R$ 60,00. Se o cliente paga R$ 40,00 agora e deixa R$ 60,00 fiados, entram R$ 40,00 no resumo e R$ 24,00 de CMV. Se pagar os R$ 60,00 no mês seguinte, nesse mês entram R$ 60,00 e os R$ 36,00 restantes de CMV. O cálculo usa a diferença do custo acumulado antes e depois de cada parcela, para não duplicar nem perder centavos.

Um estorno completo conserva a venda original, repõe o estoque e cancela o saldo ainda não pago. O valor que já foi pago aparece como devolução no mês do estorno, mesmo que a venda tenha ocorrido em outro mês. Se ainda não houve recebimento de um fiado integral, cancelar esse saldo não é uma saída de dinheiro.

Os quadros de **Volume**, **Produtos**, **Vendas** e **Por conta** continuam mostrando as vendas originais, inclusive fiado. O ranking compara quantidades e lucro histórico das vendas antes de despesas; produtos ativos sem venda aparecem com zero. Estornos não apagam essas linhas nem transformam o ranking em uma medida de dinheiro recebido.

O app registra Pix e cartões conforme o informado pelo operador; cartões entram na data da venda. Não há conciliação automática de repasses, extratos ou taxas. Registre despesas operacionais, taxas e impostos aplicáveis na área de despesas para que entrem no resultado mostrado.

## Clientes, limites e perfis

Em **☰ → Fiado → Clientes**, é possível visualizar nome, telefone, limite e saldo. O telefone também aparece junto aos fiados e participa da busca. Nome e telefone podem ser corrigidos no cadastro; alterar o limite exige administrador. Limite zero significa **sem limite definido**, não saldo disponível zero.

O vencimento é registrado na venda fiada e os atrasos ficam sinalizados. Para conceder crédito acima do limite, um administrador precisa informar um motivo. Quando os aparelhos estão offline, o app usa o saldo conhecido; o servidor reavalia o saldo na sincronização e pode recusar uma venda que ultrapasse o limite atualizado.

O primeiro administrador é definido por quem controla o Firebase, conforme [PERFIS.md](tools/admin/PERFIS.md). Depois disso, use **☰ → Perfis da equipe** para criar operadores e ajustar permissões. Criar uma conta não desconecta o administrador. Se a criação do acesso terminar, mas a gravação do perfil ficar pendente, use **Concluir cadastro**, preservando o mesmo UID.

Permissões restringem edição de preços, exclusão de produtos, relatórios, estornos e exportação. Uma conta desativada não recupera acesso pela autorização antiga `pdv`. O aparelho recebe mudanças de acesso ao reconectar; gravações pendentes continuam sujeitas às regras atuais do servidor.

## Fotos e leitura pela câmera

As fotos continuam em [fotos-produtos](fotos-produtos/README.md). Coloque os arquivos de origem nessa pasta antes de gerar a atualização e dê nomes fáceis de localizar. A geração cria cópias comprimidas dos arquivos disponíveis para o aplicativo. No cadastro, escolha a foto pelo nome; ela aparecerá em Estoque, Caixa e carrinho.

Preserve o nome de imagens já associadas aos produtos. Para substituí-las, mantenha o mesmo nome de arquivo. Acrescentar novas fotos requer uma nova versão instalada nos aparelhos. Não existe envio de fotos ao Firestore, Firebase Storage ou exigência de plano Blaze para esse catálogo.

A câmera desta versão serve para **ler códigos de barras**. O modelo de leitura é incorporado ao aplicativo, inclusive para uso sem conexão. O Android pede permissão de câmera; se ela for recusada, o operador pode continuar digitando o código ou liberar a permissão nas configurações.

## Consultas e exportação

Relatórios consultam vendas, despesas, recebimentos e estornos do mês selecionado quando essa tela está em uso. Ao sair, suas assinaturas são encerradas. O Fiado prioriza contas abertas; os recebimentos e a consulta de quitados usam o mês selecionado. O app deixa de buscar todo o histórico de vendas e recebimentos na inicialização.

Catálogo, clientes, sessão de caixa e acompanhamento essencial de pendências continuam necessários para o atendimento. Mover o botão de Relatórios para o menu, sozinho, não reduziria leituras: a mudança inclui o controle das consultas. Meses com muitos documentos e listas grandes de contas abertas ainda podem consumir muitas leituras; não há garantia de consumo fixo ou cota ilimitada.

Em **☰ → Exportar dados**, escolha o conteúdo e onde salvar. A exportação exige permissão, conexão e dados confirmados. **Vendas** exporta o mês escolhido, uma linha por venda original, incluindo fiado original e custo histórico. **Estoque**, **Clientes** e **Fiado em aberto** mostram a situação atual. O CSV de vendas não substitui o resumo de recebimentos; recebimentos posteriores e estornos têm tratamento separado no aplicativo.

## Preparação da instalação nos dois celulares

O APK de testes já teve sua geração autorizada. A preparação do banco e a instalação precisam seguir esta ordem. Não execute a migração enquanto a loja ainda precisa continuar operando com os aplicativos antigos.

1. **Preparar a versão de teste no computador.** A validação inicial está registrada em [VALIDACAO-1.2.0.md](VALIDACAO-1.2.0.md). Gerar o APK autorizado com a assinatura original e conferir que a versão é 1.2.0 (5). Preserve `br.com.bebidaspdv` e a chave usada nos celulares atuais. A pasta de atualização usada como cópia do projeto permanece separada deste trabalho.
2. **Escolher uma janela sem atendimento.** Os dois aparelhos precisam parar juntos. Conecte ambos e espere todas as vendas, entradas, cadastros e recebimentos serem sincronizados. Confira operações com resposta incerta usando o mesmo comprovante.
3. **Esvaziar os carrinhos e parar operações.** Não registre novas vendas, compras ou recebimentos durante a preparação dos dados. Não desinstale nem limpe os dados para tentar resolver uma pendência.
4. **Conferir e migrar o histórico.** Siga [MIGRACAO-V3.md](tools/admin/MIGRACAO-V3.md) em um computador confiável: primeiro execute a conferência sem gravar; depois aplique a migração. Ela adiciona saldos, situação das contas e custo proporcional dos recebimentos, sem trocar IDs, estoque ou preços históricos. Ao terminar, uma conferência deve indicar zero alterações necessárias. Interrupção exige manter a loja parada até concluir.
5. **Definir o primeiro administrador.** Siga [PERFIS.md](tools/admin/PERFIS.md) usando o UID da conta escolhida pelo responsável. A migração do histórico não concede permissões automaticamente.
6. **Publicar regras e índices revisados.** O responsável publica `firestore.rules` e `firestore.indexes.json` desta versão no projeto correto. Com a Firebase CLI já configurada, a ação correspondente é `firebase deploy --only firestore:rules,firestore:indexes --project ID_DO_PROJETO`. Aguarde a conclusão. Esses arquivos e o comando não foram publicados automaticamente no desenvolvimento.
7. **Distribuir e instalar a versão nova em ambos os aparelhos.** Siga [ATUALIZACOES.md](ATUALIZACOES.md) para a distribuição de testes pelo Firebase App Distribution. A instalação deve ser feita sobre o app existente, preservando conta e dados. Não retome o atendimento em um aparelho ainda com a versão antiga: ela não mantém os novos saldos e vínculos.
8. **Conferir a operação conectada.** Confira versão, conta, perfis, produtos, fotos, clientes, saldos de fiado e relatórios. Abra a sessão de caixa de cada aparelho antes dos testes de venda.
9. **Executar os cenários de conferência abaixo.** Só retome o uso normal quando os dois aparelhos estiverem na mesma versão e os registros dos testes tiverem sido conciliados. Não desinstale ou volte para o APK antigo para contornar falhas de migração; mantenha os registros e corrija a causa.

O Firebase App Distribution continua sendo o meio de testes escolhido. O novo fluxo de **Instalar agora** passa a existir depois que esta versão estiver instalada; se a versão antiga ainda não abrir o instalador, siga o procedimento de instalação de testes já documentado, preservando a assinatura.

## Conferência posterior nos aparelhos

A implementação no código e os testes automatizados não substituem estes cenários. **Esta conferência prática ainda não foi realizada nesta etapa.**

- Fazer o primeiro login, cadastrar um produto e ir de Estoque para Caixa sem reiniciar o aplicativo.
- Cadastrar caixa e item avulso, incluindo bebida, alimento e maço; conferir custo arredondado, custo exato da caixa, preço de venda e baixa do saldo compartilhado.
- Ler códigos com a câmera, recusar e permitir o acesso, fechar o leitor durante o processamento e usar o código digitado como alternativa.
- Adicionar quantidade pelo teclado, usar favoritos, revisar fotos e finalizar duas vendas consecutivas; a mensagem não deve bloquear a segunda.
- Conferir pagamentos divididos, troco, fiado parcial, nome/telefone, vencimento, limite e liberação administrativa com motivo.
- Vender fiado e conferir que a parte não paga fica fora do Resumo. Receber parcelas em meses diferentes e conferir receita e CMV de cada mês.
- Abrir caixas nos dois aparelhos, registrar vendas em dinheiro e em outros meios, sangria/suprimento e fechamento com diferença. Um fechamento baseado em saldo alterado deve exigir nova conferência.
- Com internet desligada após abrir a sessão, vender e depois reconectar. Conferir os comprovantes e o estoque dos dois aparelhos; validar também a sinalização de pendências ou recusas.
- Estornar uma venda à vista e uma venda parcialmente fiada. Conferir estoque, devolução, saldo cancelado, resultado do mês e impedimento de segundo estorno.
- Criar operador dentro do app, conferir que o administrador permanece conectado, testar permissões e desativação, preservando a identificação nas vendas antigas.
- Exportar os quatro tipos de CSV, abrir os arquivos e conferir quantidades separadas de valores, telefones/códigos e o mês das vendas.
- Conferir **Baixar atualização → Instalar agora**, cancelamento da instalação, permissão do Android e retomada após reabrir, usando uma versão de teste futura disponibilizada para esse fim.

## Limites mantidos

- Até **8 produtos distintos por movimentação**; quantidades inteiras de 1 a 9.999 por linha; preços e totais até 99.999.999 centavos.
- Estorno completo, sem devolução parcial de itens.
- Catálogo de fotos incorporado ao aplicativo, sem captura/envio de fotos no cadastro.
- Possibilidade de estoque negativo após dois aparelhos venderem offline o mesmo saldo; precisa de conferência ao sincronizar.
- Relatórios offline podem estar incompletos. O custo histórico usa o último cadastro conhecido no aparelho.
- Sem emissão fiscal, processamento de pagamentos, conciliação bancária ou integração com WhatsApp nesta atualização.
