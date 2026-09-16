# Revisão do leitor de código de barras — 12/09/2026

O leitor compartilhado pelo cadastro, estoque e caixa aceitava o primeiro resultado de uma única imagem. Se a câmera detectasse mais de um código, escolhia o primeiro da lista. Essa lista não representa uma classificação de confiança. O reconhecimento de um trecho das barras como outro formato, especialmente em imagens sem foco, é compatível com o relato de um código longo virar números curtos diferentes a cada tentativa. Sem imagens ou registros da câmera, não foi possível confirmar qual formato originou cada leitura no aparelho.

O [ML Kit recomenda confirmar uma sequência de resultados iguais](https://developers.google.com/ml-kit/vision/barcode-scanning/android#tips_to_improve_real-time_performance), pois o resultado pode variar entre imagens. Também informa que não valida o dígito de controle de ITF e Code 39.

## Comportamento corrigido

- O mesmo texto e formato precisam aparecer em pelo menos **cinco imagens consecutivas**, durante pelo menos **750 milissegundos**. É necessário satisfazer as duas condições.
- Uma leitura diferente, inválida, ausente ou com múltiplos códigos reinicia a confirmação. Uma pausa superior a um segundo entre imagens também exige uma nova sequência.
- Códigos curtos continuam disponíveis, nos mesmos formatos antes habilitados. Não há limite mínimo geral de tamanho nem seleção adicional de formato. ITF genérico não é tratado obrigatoriamente como GTIN-14.
- EAN e UPC são conferidos pelo comprimento e dígito de controle exigidos pelo próprio formato. UPC-E é expandido somente para validar o dígito; o valor original é preservado, assim como seus zeros iniciais. O leitor não corrige números automaticamente.
- A interface mostra que está confirmando a leitura e avisa quando há mais de um código. A análise solicita resolução próxima de 1280 × 720, com alternativa suportada pela câmera, e a prévia usa ajuste central sem preenchimento por corte.
- A entrega continua única por abertura do leitor. Resultados após o fechamento são ignorados e a imagem permanece liberada ao terminar o processamento.

## Conferência nos celulares

1. Execute o código atualizado pelo Android Studio em um aparelho de testes. O APK anteriormente entregue não é atualizado por editar os arquivos do projeto.
2. No cadastro, leia uma etiqueta longa dez vezes e compare o valor completo com o impresso.
3. Repita com uma etiqueta curta legítima, inclusive com zero inicial. Ela deve ser aceita depois de permanecer estável.
4. Movimente levemente a câmera ou alterne duas etiquetas: o leitor deve aguardar uma sequência estável, sem somar leituras de códigos diferentes.
5. Coloque dois códigos visíveis ao mesmo tempo: deve aparecer a orientação para deixar apenas um.
6. Repita a leitura na busca do estoque e do caixa. Confira também fechar/reabrir o leitor, negar a permissão e colocar o aplicativo em segundo plano.

A estabilidade reduz resultados transitórios, mas não comprova que uma interpretação consistentemente errada corresponde à etiqueta. A conferência óptica em aparelhos reais continua necessária, principalmente em etiquetas pequenas, curvas, danificadas ou com reflexos.

## Validação local

Os testes em `BarcodeReadPolicyTest.kt` cobrem estabilidade temporal, número mínimo de imagens, resultados alternados, interrupções, múltiplos códigos, validade de EAN/UPC e preservação de códigos curtos e zeros iniciais. A regressão reproduz a sequência de fragmentos descrita pelo usuário como entradas do reconhecimento, sem simular uma validação óptica real.

Os **23 testes novos do leitor passaram**, assim como os demais testes do módulo Android: **77 aprovados, zero falhas ou erros**. As compilações Kotlin de debug e release e o Android Lint também passaram. A execução terminou com `BUILD SUCCESSFUL` em 3 minutos e 42 segundos. Esses testes avaliam o código; a conferência óptica nos celulares segue pendente.

Log da compilação, testes e Lint: `../../work/barcode-validation.log`. Não foi gerado APK novo nem alterado o Firebase. O APK 1.2.0 entregue anteriormente conserva seu conteúdo original e não inclui esta correção posterior.
