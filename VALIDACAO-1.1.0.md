# Validação da versão 1.1.0 (3)

Verificação local concluída em 8 de setembro de 2026; testes das regras executados em 7 de setembro. As regras não foram alteradas depois desses testes.

## Testes automatizados

| Área | Testes aprovados |
| --- | ---: |
| Dinheiro, criação de movimentações e cálculo mensal | 23 |
| Pagamentos divididos, troco, fiado e caixas | 9 |
| Fluxo original de caixa | 5 |
| Consulta e instalação de atualizações | 10 |
| Novas funções e retomada de recebimentos | 12 |
| Rankings, contas de operadores e recebimentos nos relatórios | 6 |
| Regras do Firestore no emulador | 24 |
| **Total** | **89** |

Nenhuma falha ou teste ignorado. Os testes locais usam dados de teste e não gravam no banco da loja.

A cobertura inclui custo congelado, venda abaixo do custo, limites mensais, dez unidades digitadas, conversão de caixas para estoque, pagamento com R$ 50 em dinheiro e R$ 20 em Pix, troco somente em dinheiro, exigência de cliente cadastrado no fiado, preservação de foto e embalagem, exclusão durante um carrinho aberto, troca de conta e espera pela sincronização.

Para recebimentos, há testes de retomada com o mesmo UUID, recusa definitiva, pendência recuperada após reinício e bloqueio de um segundo pagamento igual até conferir o anterior. A regra de saldo compartilhado é exercitada por recebimentos concorrentes de dois operadores no emulador. Receber uma dívida antiga não conta como uma nova venda no faturamento.

As regras também foram verificadas com oito produtos por venda, pagamentos divididos, criação de fiado no mesmo lote, registros antigos, adulteração de operador e totais, exclusão do catálogo e imutabilidade de vendas e despesas.

## Compilação e fotos

O código Kotlin das variantes debug e release compilou. O APK de testes é gerado em debug e mantém o App Distribution; a variante release continua sem o SDK de atualização de testes.

A análise Android Lint terminou com zero erros e sete avisos de versões mais recentes de dependências. As versões fixadas foram mantidas nesta entrega. A preparação final do pacote também terminou sem falhas.

A preparação de fotos foi exercitada com um PNG panorâmico e um JPEG com orientação EXIF. As cópias ficaram limitadas a 512 pixels no maior lado, com proporção e orientação preservadas. Esses arquivos sintéticos foram retirados da pasta de fotos do projeto. Esta entrega não inclui fotos reais de produtos; a pasta `fotos-produtos` contém as orientações para acrescentá-las.

## APK entregue

- Arquivo: `../Atualizacoes/1.1.0-3/Gela-PDV-1.1.0-testes.apk`.
- Aplicativo: `br.com.bebidaspdv`, versão `1.1.0`, código `3`.
- Tamanho: 25.764.692 bytes.
- SHA-256 do APK: `c7e46eb6eec6e3f1f3f99224eb7f19b6dd980cab659cc74e747cd4e92e481561`.
- Certificado de assinatura preservado: `18802e9d8a8baf1fd92ec5bc53fbe41b5e429e8239ee687dde1283a78aba6831`.

O verificador de assinatura Android aceitou o APK. A inspeção do arquivo confirmou o catálogo de fotos vazio e a ausência das imagens sintéticas. A pasta do pacote inclui `novidades.txt` e `verificacao.json`; o registro de verificação indica que o pacote ainda não foi distribuído.

## Evidências locais

- `core/build/test-results/test` e `app/build/test-results/testDebugUnitTest`: resultados JUnit.
- `app/build/reports/lint-results-debug.html`: análise estática Android.
- `../../work/features-release-check.log`: compilação final e testes.
- `../../work/features-firestore-tests.log`: 24 testes de regras aprovados.
- `../../work/product-photos-fixture.log`: preparação das imagens de teste.
- `../../work/features-package.log`: preparação do pacote de atualização.

## Conferência ainda necessária nos aparelhos

Não houve teste visual desta versão em emulador Android ou nos dois celulares da loja, nem instalação real por App Distribution nesta etapa. As regras não foram publicadas no projeto Firebase e o APK não foi distribuído aos testadores por esta tarefa.

Siga [NOVAS-FUNCOES.md](NOVAS-FUNCOES.md) para publicar as regras, atualizar sobre a instalação atual e conferir as funções nos aparelhos. Use o mesmo banco e aguarde a sincronização antes da atualização. Os avisos de estoque são internos ao aplicativo e os recebimentos de fiado exigem conexão.
