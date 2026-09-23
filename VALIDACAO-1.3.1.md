# Validação da versão 1.3.1 (7)

Pacote gerado em **22/09/2026**; conferência final concluída em **23/09/2026**. Esta versão incorpora a correção da observação do carrinho e dos cliques rápidos descrita em [CORRECAO-CAIXA.md](CORRECAO-CAIXA.md).

## Resultado do pacote

O script `tools/distribution/Prepare-Update.ps1`, com `PreviousVersionCode=6`, terminou com **BUILD SUCCESSFUL** e preparou o APK depois de verificar testes, versão e assinatura.

| Verificação | Resultado |
| --- | --- |
| `:core:test` | 97 testes aprovados; resultado reutilizado pelo Gradle, pois o módulo não mudou. |
| `:app:testDebugUnitTest` | 120 testes aprovados, sem falhas ou erros. |
| `:app:assembleDebug` | APK 1.3.1 (7) gerado. |
| `:app:lintDebug` | Zero erros; 25 avisos: 13 sugestões de novas versões de bibliotecas, 10 `UseKtx` e 2 `ApplySharedPref`. |
| Verificação da assinatura | Assinatura válida e certificado idêntico ao APK 1.3.0 entregue. |
| Manifesto do APK | Nome, identificador, versão e SDK conferidos com `aapt`. |
| Instalação sobre a versão anterior | 1.3.0 (6) → 1.3.1 (7) aceita pelo Android sem desinstalar. |
| Arquivo local de teste | Preservado após a substituição do APK. |
| Interface do Caixa no novo APK | **10/10 testes aprovados**, em modo avião, Android 15/API 35. |

Os 13 avisos `GradleDependency` resultam da consulta de versões disponíveis nesta execução; não são erros de compilação. Foram mantidas as versões do projeto e a atualização pontual de Navigation para 2.9.1, sem atualização geral de dependências.

## Teste de atualização e interface

No emulador isolado `CartRegression`, foi instalado o APK 1.3.0 anteriormente entregue. Um arquivo identificável foi criado na pasta interna do aplicativo; em seguida foi instalado o novo APK com substituição, sem desinstalação nem limpeza. O Android confirmou versão 1.3.1 (7), e o conteúdo do arquivo permaneceu intacto. Isso comprova compatibilidade de instalação e preservação desse arquivo; não constitui um teste da sessão autenticada ou dos dados reais da loja.

A suíte funcional `CashierScreenTest`, excluindo os dois cenários `LargeTest`, foi executada contra o APK 1.3.1 instalado e terminou com **OK (10 tests)**, em **70,916 segundos**. O pacote de instrumentação existente foi reutilizado: o código dos testes e da correção não mudou desde sua compilação; a versão do aplicativo alvo foi conferida antes da execução.

Foram conferidos o conteúdo visível da tela, o botão, a digitação, adições rápidas, reduções e remoção, seleção repetida de Caixa, saída/retorno, revisão da venda, catálogo com 5.000 produtos, limite de oito produtos, total, estoque e ausência de duplicação. Os callbacks paralelos de sincronização são simulados. O catálogo e o repositório são isolados: não há gravações de negócio no Firebase nesses testes.

As verificações anteriores de tela aberta por dois minutos, painel lateral, conectividade e desempenho estão detalhadas em [CORRECAO-CAIXA.md](CORRECAO-CAIXA.md). Elas não foram contadas novamente como testes executados sobre o pacote final.

Evidências locais da preparação e da conferência, fora do repositório distribuído:

- `work/cart-probe/release-1.3.1-build.log`.
- `work/cart-probe/update-1.3.1-installation.json`.
- `work/cart-probe/update-1.3.1-ui-tests.txt`.
- `work/cart-probe/update-1.3.1-logs/`: dez arquivos de eventos dos cenários executados.

## Arquivo entregue

- Arquivo: `outputs/PDV-Hora-da-Gelada-1.3.1.apk`, ao lado da pasta do projeto `BebidasPDV`.
- Cópia preparada pelo script: `distribution/build/1.3.1-7/PDV-Hora-da-Gelada-1.3.1.apk`.
- Aplicativo: **PDV Hora da Gelada**.
- Identificador: `br.com.bebidaspdv`.
- Versão: **1.3.1**, código **7**, variante **debug**, preservando o canal de testes do Firebase App Distribution.
- Android mínimo: API 26; destino: API 35.
- Tamanho: **48.987.580 bytes**.
- SHA-256 do APK: `eb04daa81cba1e907a39a33bbf46fd165b70aa925499956033752eb5fcc3962c`.
- SHA-256 do certificado: `18802e9d8a8baf1fd92ec5bc53fbe41b5e429e8239ee687dde1283a78aba6831`.

O APK 1.3.0 permanece preservado como histórico. A geração e a verificação da 1.3.1 não a publicam no Firebase App Distribution nem atualizam automaticamente os celulares. As notas para distribuição estão em `distribution/notes-1.3.1.txt` e na cópia `distribution/build/1.3.1-7/novidades.txt`.

## Limites e conferência nos celulares

A validação funcional da correção passou. A aprovação de desempenho em todos os cenários continua parcial: na investigação anterior, o primeiro desenho do painel lateral debug ultrapassou 500 ms em algumas execuções, inclusive 979 ms na comparação final. Desativar depuração melhorou aquela amostra, mas a variante experimental não foi adotada para esta distribuição. Não foram inseridos atrasos, tentativas artificiais ou mudanças de layout para ocultar a medição.

Os dois celulares físicos ainda precisam de conferência com as contas e o ambiente reais. Siga [INSTALAR-1.3.1.md](INSTALAR-1.3.1.md), primeiro em um aparelho. Quem já utiliza a 1.3.0 não precisa publicar novas regras ou índices nem migrar o banco para esta atualização.
