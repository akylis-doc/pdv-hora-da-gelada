# Validação da versão 1.0.1 (2)

Verificações locais realizadas em 7 de setembro de 2026.

- Compilação do APK debug e compilação Kotlin da variante release concluídas.
- 38 testes automatizados passaram: 23 de domínio, 5 das operações do PDV e 10 do fluxo de atualização.
- Lint Android: zero erros. Sete avisos informam versões mais recentes de bibliotecas já usadas pelo projeto; elas foram mantidas nesta entrega para limitar o escopo da mudança.
- APK assinado e verificado com apksigner. O identificador continua `br.com.bebidaspdv`, com `versionCode = 2` e `versionName = 1.0.1`.
- O certificado SHA-256 coincide com o APK anterior disponível neste computador: `18802e9d8a8baf1fd92ec5bc53fbe41b5e429e8239ee687dde1283a78aba6831`.
- O manifesto combinado de release não contém o SDK App Distribution nem suas permissões de instalação/notificação. O debug contém o SDK de testes.
- O preparador `Prepare-Update.ps1` foi executado com código anterior 1 e concluiu a separação do APK 1.0.1 (2), notas e comprovante de verificação em `outputs/Atualizacoes/1.0.1-2`. Ele não publica no Firebase.

Os testes de atualização cobrem consulta manual, ausência de instalação automática, falha de consulta, toques repetidos, carrinho aberto, gravações pendentes, cache offline, trava de operações durante a atualização, falhas/tempo limite e troca da conta de testes. Também conferem que o caixa volta a aceitar operações após falha ou conclusão da tarefa do SDK.

Não foram alteradas as regras, coleções ou a lógica de gravação do Firestore nesta entrega. Os testes de regras da entrega anterior não foram repetidos para esta mudança de atualização.

## Validação ainda necessária na conta e nos celulares

A distribuição não foi publicada, nenhum convite foi enviado e a API não foi ativada remotamente por esta tarefa. A aceitação do convite, autenticação da conta de testes, download real, confirmação/cancelamento da instalação, aparência da tela no aparelho e manutenção dos dados após atualizar precisam ser conferidos nos celulares. A assinatura foi comparada ao APK local anterior; não foi extraída dos celulares.

Siga `ATUALIZACOES.md` para ativar o canal, distribuir a 1.0.1 e preparar os dois aparelhos. Para comprovar a atualização dentro do app, distribua depois uma versão com código maior e valide primeiro em um aparelho de teste.
