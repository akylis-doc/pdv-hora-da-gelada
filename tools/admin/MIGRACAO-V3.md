# Preparação dos dados para a nova versão

Esta atualização passa a mostrar o resumo financeiro conforme os recebimentos, incluindo o CMV proporcional de cada pagamento de fiado. Ela também usa o saldo agregado por cliente, contas abertas/quitadas e recebimentos por forma de pagamento. O histórico antigo precisa receber esses campos complementares antes de operar com a versão nova.

O script `migrate-v3.mjs` não altera preços históricos, estoque, valores de vendas, valores pagos, nomes, telefones, permissões ou contas de acesso. Ele conserva todos os IDs. Não gera APK, não instala aplicativos nem publica regras. Esta preparação não foi executada no seu Firebase durante o desenvolvimento.

## Antes de aplicar

1. Escolha um horário em que a loja possa ficar sem registrar operações nos dois celulares.
2. Deixe os dois aparelhos conectados e aguarde a sincronização de vendas, entradas, cadastros e recebimentos pendentes. Confira também pendências de recebimentos e operações de caixa; uma operação incerta precisa ser conferida antes de continuar.
3. Feche os carrinhos e pare de operar em ambos os aparelhos. Não use a versão antiga depois da migração: ela não atualiza os novos saldos agregados. Mantenha os aparelhos parados até concluir a migração, publicar as regras revisadas e instalar a mesma versão nova nos dois.
4. No computador confiável do responsável, configure as credenciais administrativas conforme `PERFIS.md`. Nunca copie a credencial para o projeto, para a pasta de fotos ou para o aplicativo.
5. Abra um terminal na pasta `tools/admin`. Instale as dependências com `npm ci` caso ainda não estejam instaladas.

## Conferir sem mudar dados

Substitua `ID_DO_PROJETO` pelo projeto da sua loja:

```powershell
node migrate-v3.mjs ID_DO_PROJETO
```

O script lê clientes, contas, vendas, recebimentos e estornos. Ele mostra quantos documentos precisam ser complementados e verifica a conciliação **antes de iniciar qualquer gravação**. Essa leitura usa a cota do Firestore; evite repetir sem necessidade quando houver um histórico grande.

Se houver conta sem venda/cliente, recebimentos que não somam o valor baixado, CMV incompatível com os itens, saldo agregado já existente incorreto ou estorno inconsistente, a execução para e identifica o registro pelo caminho/ID. Não há ajuste automático de dinheiro para esconder divergências. Confira os comprovantes e peça a correção da inconsistência antes de aplicar.

## Aplicar depois da conferência

Com os dois aparelhos parados e sincronizados:

```powershell
node migrate-v3.mjs ID_DO_PROJETO --apply
```

O script confere o histórico novamente e grava por transação. Cada conta é complementada junto com os seus recebimentos; depois, cada cliente recebe o saldo conciliado. Mudanças detectadas enquanto a execução está em andamento interrompem a migração. Uma conta com mais de 450 documentos a complementar também interrompe a etapa antes das gravações e precisa de migração assistida.

Ao terminar, execute uma vez sem `--apply` para confirmar **zero alterações necessárias**. Só retome a loja depois de concluir a publicação das regras revisadas e a instalação da nova versão em ambos os celulares. Esses passos são separados e precisam ser executados pelo responsável na preparação da distribuição; nenhum foi feito automaticamente agora.

Se a internet ou o computador falhar, as transações já concluídas continuam válidas. Mantenha os celulares parados, resolva a causa e execute a conferência outra vez. A execução repetida não cria vendas ou pagamentos e não duplica valores. O procedimento não é uma transação única de todo o banco; por isso, não reabra a operação entre uma interrupção e a conclusão.

## Campos complementados

| Coleção | Complementos |
| --- | --- |
| Clientes | `credit_limit_cents` (zero significa sem limite), preservando limites existentes; `balance_cents`, somando os saldos abertos. |
| ContasReceber | `settled_by_method_cents` com dinheiro, Pix, débito e crédito; `closed`; `cancelled_cents`; `due_epoch_day` (zero para vencimento antigo não informado); referência de estorno quando aplicável. |
| Recebimentos | `paid_before_cents` e `cost_recognized_cents`, usando o custo congelado da venda. |

Para cada recebimento, o CMV reconhecido é a diferença entre o custo proporcional acumulado após e antes do pagamento, com arredondamento HALF_UP em centavos. Isso evita perder ou repetir centavos em vários pagamentos parciais. Um `paid_before_cents` já existente preserva a ordem registrada pelas transações, mesmo se os relógios dos celulares estiverem diferentes. Recebimentos antigos sem essa informação são ordenados pela data/hora e, em caso de empate, pelo ID.

Vendas à vista antigas continuam legíveis. Esta migração não cria sessões de caixa fictícias para vendas históricas e não concede perfil de administrador. A primeira conta administrativa é preparada separadamente conforme `PERFIS.md`.
