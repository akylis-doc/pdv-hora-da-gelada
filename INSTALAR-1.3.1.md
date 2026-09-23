# Instalar a versão 1.3.1 (7)

Esta atualização inclui a correção visual do carrinho e dos botões de quantidade. O arquivo de entrega é **PDV-Hora-da-Gelada-1.3.1.apk**, em `outputs`, ao lado do projeto `BebidasPDV`. A cópia preparada pelo script fica em `BebidasPDV/distribution/build/1.3.1-7`.

## Atualizar os celulares

1. Termine a venda em andamento e confira se as operações anteriores sincronizaram.
2. Abra o novo APK no celular e escolha **Atualizar**. Se o Android solicitar, permita a instalação pela origem que abriu o arquivo.
3. **Não desinstale o aplicativo nem limpe os dados.** O identificador e a assinatura foram mantidos para a atualização sobre a instalação existente.
4. Abra **☰ → Ajustes** e confira a versão **1.3.1 (7)**. A conta conectada deve continuar disponível.
5. No Caixa, selecione Caixa novamente, adicione um produto, pressione várias vezes o botão de adicionar, digite uma quantidade e reduza-a. Confira os valores visíveis e o total, sem finalizar uma venda apenas para fazer esse teste. Limpe esse carrinho ao terminar.
6. Saia para Estoque e volte ao Caixa. Confira também em modo avião com o catálogo já disponível. Reative a conexão após o teste.
7. Atualize o segundo celular e confira ambos antes de retomar o uso normal.

Se o Android informar assinatura incompatível, não desinstale para contornar o problema. Confira se o arquivo instalado anteriormente pertence à mesma distribuição. Não tente voltar à 1.3.0 por cima desta versão; uma correção posterior deve ter código maior.

## Receber pelo Firebase App Distribution

No App Distribution do mesmo projeto e aplicativo Android `br.com.bebidaspdv`, envie o APK 1.3.1 (7), copie as notas de `distribution/notes-1.3.1.txt` e distribua aos testadores já autorizados. Nos celulares, consulte **☰ → Ajustes → Atualizações**, baixe a versão e confirme a instalação no Android.

Gerar este APK não publica nem distribui automaticamente a versão. O canal do App Distribution foi preservado. As instruções gerais estão em [ATUALIZACOES.md](ATUALIZACOES.md).

## Banco e diagnóstico

Quem já usa a 1.3.0 **não precisa publicar regras, criar índices ou migrar dados** para instalar a 1.3.1. Quem vem de versões anteriores à 1.2.0 ainda deve seguir a preparação daquela versão.

Em **☰ → Ajustes → Diagnóstico → Abrir Logs**, cada adição, digitação ou remoção possui seu próprio `trace_id`. Compare `STATEFLOW_EMITTED`, `CASHIER_SCREEN_STATE_RECEIVED` e `CART_RENDERED` da mesma revisão. Veja a investigação e os limites medidos em [CORRECAO-CAIXA.md](CORRECAO-CAIXA.md), e a conferência do pacote em [VALIDACAO-1.3.1.md](VALIDACAO-1.3.1.md).

O pacote mantém a variante de testes usada pelo canal existente. A variante sem depuração usada na investigação não foi transformada em configuração oficial de distribuição. A atualização foi testada funcionalmente em emulador; a primeira montagem do painel lateral debug ainda ultrapassou 500 ms em algumas medições. A conferência nos aparelhos físicos continua necessária.
