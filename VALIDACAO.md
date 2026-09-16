# Validação e aceitação

## Resultado executado em 06/09/2026

| Verificação | Resultado |
| --- | --- |
| Domínio Kotlin (JUnit) | 23 testes, zero falhas |
| Fluxo de caixa/ViewModel (JUnit) | 5 testes, zero falhas |
| Regras no Firestore Emulator 1.19.8 | 15 testes, zero falhas |
| Compilação Android debug | APK gerado, pacote `br.com.bebidaspdv`, versão 1.0.0 |
| Assinatura APK | Verificada com apksigner, esquema v2 |
| Android mínimo / alvo | API 26 / API 35 |
| Android Lint | Zero erros; 7 avisos de versões mais recentes de bibliotecas |

Os avisos restantes são sugestões de atualização; as versões fixadas foram compiladas e testadas em conjunto. A configuração de backup também foi verificada: cache e comprovantes locais estão excluídos do backup e da transferência entre dispositivos.

O APK entregue é **de configuração**, compilado sem `google-services.json`: abre as orientações de implantação. Para operar o caixa, configure seu Firebase e gere uma nova instalação. O código do PDV está incluído e foi compilado, mas não foi executado em dois aparelhos nem conectado ao Firebase da loja.

## Como reproduzir

Com JDK 17 e SDK Android 35 instalados:

```text
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

No Windows, use `.\gradlew.bat` no lugar de `./gradlew`.

Para as regras, na pasta `tests/firestore`, com Node 20+ e Java 17:

```text
npm install
npm test
```

A suíte inicia o Firestore Emulator com ID `demo-bebidas-pdv`; não requer credenciais nem escreve em um projeto real. O limite de 8 produtos deve ser testado junto às regras: testes puramente de domínio não comprovam o orçamento de acessos das regras.

## Roteiro nos dois aparelhos

Estes passos exigem dois Androids/emuladores com a configuração Firebase da loja. Execute primeiro em um projeto de homologação.

1. **Preparação:** autorize duas contas. Entre nos dois aparelhos conectados. Cadastre um produto a R$ 3,50 de custo e R$ 6,00 de venda; confirme saldo inicial zero. Registre entrada de 10 unidades e aguarde ambos exibirem saldo 10.
2. **Venda e relatório:** venda 2 unidades. Espere estoque 8 nos dois aparelhos; receita R$ 12,00, CMV R$ 7,00, resultado R$ 5,00, 2 unidades e 1 venda. A entrada não conta como faturamento.
3. **Custo histórico:** altere o custo para R$ 4,00 e venda 1 unidade. O mês deve mostrar receita R$ 18,00, CMV R$ 11,00, resultado R$ 7,00, 3 unidades e 2 vendas. A primeira venda continua com custo histórico R$ 3,50.
4. **Modo avião:** desligue a rede em um aparelho, venda 1 unidade e verifique que o botão libera após o registro local, sem aguardar internet. A operação deve aparecer pendente. Feche o app, encerre o processo e abra novamente ainda offline: confirme os dados locais. Restabeleça a rede; a mesma operação deve confirmar sem criar outro UUID.
5. **Concorrência:** com apenas 1 unidade sincronizada nos dois aparelhos, desconecte ambos e venda 1 em cada um. Reconecte os dois. Espere dois comprovantes distintos e saldo −1, acompanhado do aviso de conferência. Não espere bloqueio global de estoque enquanto offline.
6. **Recusa tardia:** em homologação, enfileire uma venda offline e revogue a claim do operador, invalidando seu token antes de sincronizar. Confira recusa, reversão do saldo otimista e preservação do resumo local com quantidade, itens, preço e custo. Não refaça até conferir o comprovante.
7. **Integridade:** tente gravar somente um delta de estoque, uma venda sem delta, reaplicar o mesmo UUID ou editar uma venda existente. Todas essas escritas devem ser recusadas e nenhum saldo deve mudar parcialmente.
8. **Relatório parcial:** abra um mês ainda não carregado, sem rede. Confirme o aviso de cache possivelmente incompleto. Conecte e confira as vendas dos dois aparelhos no mesmo período.
9. **Interface:** confira retrato e paisagem, tela com largura a partir de 840 dp, temas claro e escuro, teclado aberto, textos ampliados, diálogo de entrada, carrinho longo e lista de sincronização com mais de 6 operações. Valores devem exibir R$; contagens devem continuar inteiras.
10. **Toques repetidos:** toque duas vezes em finalizar enquanto o registro está sendo feito. Deve surgir uma única movimentação. Após confirmação local, o carrinho deve ficar vazio e o próximo cliente pode ser atendido.

## Limites da verificação

Compilar e passar testes de regras não substitui os testes de persistência real, encerramento de processo, autenticação e interface nos dois celulares. Não houve implantação no Firebase da loja, fornecimento de contas, emissão fiscal nem transação financeira real durante o desenvolvimento.

Cadastros/edições de produtos usam a fila persistente do Firestore e exibem pendências na consulta de produtos. Sua recusa tardia é registrada enquanto o processo estiver vivo; ao contrário das movimentações, não há comprovante durável próprio para recuperar detalhes de uma edição recusada após encerrar o processo. Confira o cadastro sincronizado antes de usar um novo produto em operação real.
