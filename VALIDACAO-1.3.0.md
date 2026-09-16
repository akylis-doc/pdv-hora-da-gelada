# Validação da versão 1.3.0 (6)

Conferência em **16/09/2026**. Este documento se refere ao código atual de Logs e Diagnóstico e à correção do leitor. Os documentos e `verification-results.json` das versões anteriores permanecem como histórico, não como comprovação dos testes desta versão.

## Resultado comprovado

A execução completa terminou com **BUILD SUCCESSFUL**. Os relatórios XML contabilizam **213 testes aprovados**, sem falhas, erros ou testes ignorados.

| Verificação | Resultado |
| --- | --- |
| `:core:test` | 97 testes aprovados; inclui 25 de diagnóstico local e 7 de privacidade. |
| `:app:testDebugUnitTest` | 116 testes aprovados. |
| `:app:lintDebug` | Zero erros e 12 avisos: 10 sugestões `UseKtx` e 2 `ApplySharedPref`. |
| Kotlin debug e release | Compilação concluída. |
| `:app:assembleDebug` | APK gerado. |
| `apksigner verify --verbose --print-certs` | Assinatura válida, mesmo certificado das versões anteriores. |
| `aapt dump badging` | Pacote, nome, versão e SDK conferidos. |

Os testes locais exercitam níveis, exceções/stack trace, correlação, contextos online/offline, grande volume, filtros, busca, limpeza, retenção, exportação, reabertura do armazenamento, recuperação de registros e detecção de etapas lentas. Os testes do módulo Android cobrem também callbacks locais/remotos, filtros e rastreamento, ViewModel de diagnóstico, delegação do handler de crash, atualização, carrinho/venda e abertura/fechamento de caixa.

Foi corrigida a importação de `assertNotEquals` em `PosViewModelTest`. O teste de propagação de exceções agora verifica tipo, mensagem e presença da exceção original na cadeia de causas, preservando a verificação quando as corrotinas recuperam a pilha e criam uma cópia da exceção. Nenhuma regra de venda foi alterada por essas correções nos testes.

A seleção para o repositório foi revisada: configurações Firebase, `local.properties`, chaves de assinatura, credenciais administrativas, APKs, caches, registros locais e dependências instaladas ficam fora do GitHub. O arquivo de teste que contém uma chave privada fictícia verifica somente o mascaramento; não é uma credencial real.

## Reproduzir a validação

Use JDK 17 e SDK 35. Na pasta do projeto:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:compileReleaseKotlin :app:assembleDebug --no-daemon --console=plain
```

O APK é produzido em `app/build/outputs/apk/debug/app-debug.apk`. A chave original é obrigatória: `verifyDebugSigningKey` compara o certificado com `distribution/signing-certificate.sha256`.

## Arquivo entregue

- Arquivo: `outputs/PDV-Hora-da-Gelada-1.3.0.apk`, ao lado da pasta `BebidasPDV`.
- Aplicativo: **PDV Hora da Gelada**, pacote `br.com.bebidaspdv`.
- Versão: **1.3.0**, código **6**; variante debug para a distribuição de testes existente.
- Android mínimo: API 26; destino: API 35.
- Tamanho: **48.970.969 bytes**.
- SHA-256 do APK: `c9fb6984774d68a4dec6f6885b41d401bae28d96ea1b2c0bcbc710d107e2c33c`.
- SHA-256 do certificado: `18802e9d8a8baf1fd92ec5bc53fbe41b5e429e8239ee687dde1283a78aba6831`.

A versão e a assinatura permitem atualizar a instalação anterior deste projeto sem desinstalar. A geração do APK não o envia automaticamente ao Firebase App Distribution.

## Conferência Android e Firebase

Não foram executados testes físicos nos dois celulares nem crash real do processo Android nesta validação. Os testes de repositório usam callbacks simulados; o projeto utiliza a fila interna persistente do Firestore, sem uma SyncQueue própria.

Antes de usar na loja, conclua os cenários de [DIAGNOSTICO.md](DIAGNOSTICO.md): atualização sem desinstalar, permissões, exportação, filtros, venda offline com caixa já aberto, reinício offline, sincronização posterior, conferência nos dois aparelhos, abertura/fechamento online e leitura de etiquetas reais. A página de diagnóstico não publica regras, índices ou dados no Firebase.
