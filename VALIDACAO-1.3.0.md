# Validação da versão 1.3.0 (6)

Conferência em **16/09/2026**. Este documento se refere ao código atual de Logs e Diagnóstico e à correção do leitor. Os documentos e `verification-results.json` das versões anteriores permanecem como histórico, não como comprovação dos testes desta versão.

## Resultado comprovado

- `:core:test`: **97 testes, zero falhas, zero erros**, conforme os relatórios XML gerados em 16/09/2026. Incluem 25 testes de diagnóstico local e 7 de privacidade, além dos testes existentes de domínio.
- Os testes locais exercitam níveis, exceções/stack trace, correlação, contextos online/offline, grande volume, filtros, busca, limpeza, retenção, exportação, reabertura do armazenamento, recuperação de registros e detecção de etapas lentas.
- A compilação Kotlin das variantes debug e release foi concluída nas verificações intermediárias. Isso não equivale a concluir o APK nem os testes do módulo Android.
- A seleção para o repositório foi revisada: configurações Firebase, `local.properties`, chaves de assinatura, credenciais administrativas, APKs, caches, registros locais e dependências instaladas ficam fora do GitHub. O arquivo de teste que contém uma chave privada fictícia verifica somente o mascaramento; não é uma credencial real.

## Etapas ainda pendentes

A verificação completa encontrou `java.nio.file.AccessDeniedException` na etapa `:app:compileDebugJavaWithJavac`, ao resolver o caminho real de uma biblioteca local. Um teste Java isolado reproduziu a falha em `Path.toRealPath()` nos diretórios do projeto. A leitura direta do arquivo funciona, mas a consulta dos diretórios pais é negada no ambiente automatizado, inclusive após concessões de leitura.

Por isso, **os novos testes do módulo `app` ainda não foram executados até o fim e o APK 1.3.0 ainda não foi gerado**. Não reutilize os relatórios antigos para considerar estas etapas aprovadas. A verificação final de lint também precisa ser concluída.

Os testes adicionados ao módulo Android cobrem callbacks locais/remotos, filtros e rastreamento, ViewModel de diagnóstico, delegação do handler de crash, atualização, carrinho/venda e abertura/fechamento de caixa. Ter esses testes no código não significa que já passaram.

Para concluir em um terminal com acesso normal ao projeto, execute com JDK 17 e SDK 35:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:compileReleaseKotlin :app:assembleDebug --no-daemon --console=plain
```

O APK é produzido em `app/build/outputs/apk/debug/app-debug.apk` somente após a compilação. A chave original é obrigatória: `verifyDebugSigningKey` compara o certificado com `distribution/signing-certificate.sha256`. A impressão digital esperada é `18802e9d8a8baf1fd92ec5bc53fbe41b5e429e8239ee687dde1283a78aba6831` (SHA-256 do certificado, não do APK).

## Conferência Android e Firebase

Não foram executados testes físicos nos dois celulares nem crash real do processo Android nesta validação. Os testes de repositório usam callbacks simulados; o projeto utiliza a fila interna persistente do Firestore, sem uma SyncQueue própria.

Antes de usar na loja, conclua os cenários de [DIAGNOSTICO.md](DIAGNOSTICO.md): atualização sem desinstalar, permissões, exportação, filtros, venda offline com caixa já aberto, reinício offline, sincronização posterior, conferência nos dois aparelhos, abertura/fechamento online e leitura de etiquetas reais. A página de diagnóstico não publica regras, índices ou dados no Firebase.
