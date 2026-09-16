# Hora da Gelada PDV 1.1.1 — guia de funções

A versão **1.1.1 (4)** muda apenas a marca de Gela PDV para **Hora da Gelada PDV**. Todas as funções introduzidas na 1.1.0 continuam disponíveis: fotos no catálogo, caixas e unidades com saldo compartilhado, alertas de estoque, pagamentos divididos, troco, clientes, fiado, despesas, relatórios detalhados e configurações. Mantém o projeto Firebase da loja, o login salvo e as atualizações pelo App Distribution.

## Antes de colocar nos dois celulares

As funções introduzidas na 1.1.0 precisam das **regras atualizadas do Firestore**, que permanecem iguais na 1.1.1. **Se você já publicou as regras da 1.1.0, não precisa republicá-las apenas pela mudança de nome**; nesse caso, pule as etapas 2 e 3. Quem vem de uma versão anterior ainda precisa publicá-las: não basta instalar o APK, pois as regras antigas recusam os campos novos e as coleções de clientes, despesas e recebimentos.

1. Nos dois celulares, termine o atendimento e aguarde a sincronização de todas as operações. Confira também as operações recusadas.
2. No computador, abra o Firebase Console, selecione o mesmo projeto da loja e entre em **Firestore Database → Regras**.
3. Guarde uma cópia das regras atuais. Abra o arquivo `firestore.rules` desta versão, copie seu conteúdo completo para o editor do Firebase e publique.
4. Não recrie o banco nem os produtos. As coleções adicionais aparecem conforme você usa as funções. Os campos ausentes nos registros antigos têm valores padrão no aplicativo novo.
5. Distribua o APK **Hora-da-Gelada-PDV-1.1.1-testes.apk**, código **4**, pelo mesmo Firebase App Distribution que você já configurou. Use `distribution/notes-1.1.1.txt` como notas da versão.
6. Instale sobre o aplicativo existente. Confira um aparelho e depois atualize o outro. Atualize ambos antes de começar a usar caixas, fiado e despesas: versões anteriores à 1.1.0 não exibem todas essas informações.
7. Abra **Ajustes** e confirme a versão **1.1.1 (4)**. Confira o nome Hora da Gelada PDV, estoque, histórico e conta conectada.

Se ainda precisar publicar as regras e preferir usar o terminal, na pasta BebidasPDV execute o comando abaixo, substituindo o identificador pelo projeto já usado pela loja:

```text
firebase deploy --only firestore:rules,firestore:indexes --project ID_DO_SEU_PROJETO
```

O código e o preparador de APK não publicam regras nem enviam convites automaticamente. As regras mantêm a exigência de contas autorizadas com `pdv: true`. Esta entrega não usa Storage, Cloud Functions nem exige Blaze.

## Fotos: onde colocar e como usar

A pasta fica diretamente dentro do projeto:

```text
BebidasPDV/
  fotos-produtos/
    cerveja-lata-350ml.jpg
    refrigerante-2l.png
    agua-500ml.jpg
```

Os nomes acima são exemplos. Coloque suas próprias fotos JPG, JPEG ou PNG nessa pasta. Não há fotos reais de produtos incluídas enquanto você não fornecer os arquivos.

Na compilação, as imagens são reduzidas proporcionalmente para até 512 pixels e comprimidas. O aplicativo recebe um catálogo pesquisável pelo nome do arquivo. No cadastro ou edição do produto, toque em **Escolher foto**, pesquise e confirme a imagem. Ela aparecerá no estoque, no caixa e na revisão da venda.

Uma foto nova só chega aos celulares depois de gerar e distribuir outro APK, com código de versão maior. Não é necessário enviar cada foto ao Firebase. Mantenha os nomes dos arquivos estáveis depois de associá-los aos produtos. Veja `fotos-produtos/README.md` para formatos, nomes duplicados e substituição de imagens.

## Estoque, caixas e alertas

Cadastre cada bebida uma vez. O saldo é sempre contado em **unidades**. Informe a quantidade de unidades da caixa, o custo de cada unidade e o preço de venda por unidade. O preço da caixa pode ser informado separadamente; em branco ou zero, o aplicativo usa a soma dos preços unitários.

Exemplo: uma caixa tem 12 latas. Dar entrada em 3 caixas acrescenta 36 unidades. Vender uma caixa baixa 12 unidades; vender uma lata baixa 1. Os custos das caixas são calculados a partir do custo unitário e ficam congelados no registro da venda.

O campo **alerta de estoque** define quando o produto está acabando. Saldo igual ou menor que o limite entra em estoque baixo; saldo zero ou negativo entra em falta. A aba Estoque mostra a quantidade de alertas e permite filtrar os produtos. Os avisos refletem o saldo conhecido pelo aparelho e mudam com a sincronização.

Para excluir um produto, use a opção no estoque e confirme. Ele sai do catálogo e do caixa; o registro interno permanece inativo para preservar vendas, entradas e a sincronização com o outro aparelho. Confira o saldo físico antes de excluir. Para voltar a comercializá-lo, faça um novo cadastro.

## Caixa e pagamentos

No catálogo, escolha **Unidade** ou **Caixa**, quando houver embalagem cadastrada. Digite a quantidade ou use os botões grandes de mais e menos. A revisão do carrinho reúne itens, fotos, total, cliente e pagamento. Para alterar uma quantidade, volte ao catálogo.

Para uma compra de R$ 70,00 paga com R$ 50,00 em dinheiro e R$ 20,00 no Pix:

1. Abra **Revisar**.
2. Escolha **Dinheiro**, digite `50` e toque em **Aplicar**.
3. Escolha **Pix**, digite `20` e toque em **Aplicar**.
4. Confira o restante zerado e finalize.

Cada forma tem uma linha. Aplicar novamente a mesma forma substitui seu valor; o botão ao lado da linha permite removê-la. **Preencher restante** ajuda a completar a compra. Se receber R$ 100,00 em dinheiro para uma compra de R$ 70,00, o troco é R$ 30,00. Pix e cartão não geram troco e não podem ultrapassar o total.

O nome do cliente é opcional em uma venda normal. Para fiado, selecione um cliente cadastrado ou cadastre nome e, se desejar, telefone. É possível pagar uma parte agora e marcar somente o restante como **Fiado**. Não há cobrança automática por WhatsApp nesta versão.

O limite existente de oito produtos diferentes por movimentação foi mantido. A quantidade de cada produto é informada diretamente, inclusive dez ou mais unidades; não precisa tocar em adicionar dez vezes.

## Receber um fiado

Abra a aba **Fiado**, localize o cliente e a venda e toque em receber. Informe o valor parcial ou total e a forma de pagamento. O aplicativo confirma o saldo no servidor e registra um comprovante separado com data, hora e operador.

Essa baixa precisa de internet. Se dois celulares tentarem receber o mesmo saldo, a confirmação do servidor impede receber além do que falta. Se a resposta ficar incerta, use **Conferir pendência** na aba Fiado, inclusive após reabrir o aplicativo. O identificador e o valor do recebimento ficam guardados no aparelho para conferir ou concluir a mesma operação sem duplicação. Resolva a pendência antes de registrar outro valor para aquela venda, trocar de conta ou instalar outra versão.

Vender fiado pode funcionar offline com o cliente e os produtos disponíveis no aparelho. Receber essa dívida exige uma transação online, conforme o comportamento do [Firestore](https://firebase.google.com/docs/firestore/manage-data/transactions).

## Relatórios e despesas

Em **Relatórios**, escolha o mês e a seção desejada:

- **Resumo:** faturamento, custo das mercadorias, despesas e resultado após essas despesas. Quantidades e número de vendas aparecem separados dos valores financeiros.
- **Produtos:** ranking de mais vendidos, menos vendidos e maior lucro gerado. Uma caixa conta como suas unidades para comparar o volume. Produtos do catálogo sem venda no mês também aparecem.
- **Vendas:** cada venda com data, hora, valor, operador, cliente e detalhes de itens e pagamentos. Registros antigos sem operador ou pagamento são identificados como tal; o aplicativo não inventa esses dados.
- **Por conta:** resultados das vendas agrupados pelo operador conectado no momento do registro.
- **Despesas:** registre água, energia, aluguel ou outros custos, com descrição, data e valor.

Recebimentos são separados de faturamento: receber hoje um fiado vendido no mês anterior aumenta o dinheiro recebido hoje, sem contar a venda novamente. O resultado após despesas usa somente as despesas que você registrou; taxas ou impostos não cadastrados não são deduzidos automaticamente.

## Configurações e atualizações

Em **Ajustes**, você vê a conta conectada, a versão do app e o botão de atualizações. O login continua salvo ao fechar o app. **Entrar em outra conta** exige carrinho vazio e sincronização confirmada; use uma conta da loja que já esteja autorizada no Firebase.

A conta Google do App Distribution continua independente da conta do caixa. Para as próximas distribuições, siga `ATUALIZACOES.md`. Antes da próxima compilação com mudanças ou fotos novas, aumente `versionCode`, atualize `versionName` e crie as notas correspondentes em `distribution`. Por exemplo, para preparar a **1.1.2 (5)** depois de distribuir a **1.1.1 (4)**, execute:

```powershell
.\tools\distribution\Prepare-Update.ps1 -PreviousVersionCode 4
```

Use sempre em `PreviousVersionCode` o maior código já distribuído.

## Conferência nos aparelhos

Antes de liberar esta versão para o atendimento, confira nos dois celulares: estoque já existente, entrada e venda por caixa/unidade, pagamento dividido, troco, venda fiada e recebimento parcial, relatório com despesa e troca de operador. Faça a conferência offline e depois aguarde a sincronização. Os testes automatizados acompanham a entrega, mas não substituem essa conferência com a configuração real da loja.
