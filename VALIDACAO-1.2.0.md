# Validação da versão 1.2.0 (5)

Conferência concluída em 11/09/2026 para o projeto **PDV Hora da Gelada**.

## Resultado

| Conferência | Resultado |
| --- | --- |
| Compilação Kotlin da variante de testes | Aprovada |
| Compilação Kotlin da variante de produção | Aprovada |
| Testes Kotlin/JVM do domínio e aplicativo | 119 aprovados, nenhuma falha |
| Regras no emulador local do Firestore | 47 aprovados, nenhuma falha |
| Planejamento da migração em testes Node locais | 11 aprovados, nenhuma falha |
| Classes duplicadas nas dependências Android | Nenhuma duplicação encontrada |
| Android Lint | Concluído sem erros; 21 avisos |

São **177 testes automatizados aprovados**. Os avisos do Lint são sugestões de versões mais recentes de bibliotecas, uso de extensões Kotlin e preferências gravadas com `commit()`. O uso de gravação síncrona nas confirmações persistentes é intencional: o aplicativo precisa conferir a gravação antes de continuar. As bibliotecas continuam fixadas para não incluir atualizações adicionais nesta entrega.

Na validação inicial, as tarefas executadas foram `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:core:test`, `:app:testDebugUnitTest`, `:app:checkDebugDuplicateClasses` e `:app:lintDebug`. Nessa etapa não foi executada tarefa de geração de APK ou AAB. Arquivos intermediários de compilação e relatórios de testes não são instaladores. Posteriormente, em 11/09/2026, o responsável autorizou gerar o APK de testes, em uma etapa separada.

## APK de testes gerado após autorização

Em 11/09/2026, `:app:assembleDebug` terminou com sucesso. O arquivo está em [PDV-Hora-da-Gelada-1.2.0.apk](../PDV-Hora-da-Gelada-1.2.0.apk), com 48.712.370 bytes. A conferência com `aapt` confirmou `br.com.bebidaspdv`, versão `1.2.0`, código `5` e nome `PDV Hora da Gelada`. O `apksigner` validou a assinatura, cujo certificado coincide com `distribution/signing-certificate.sha256`.

- SHA-256 do certificado: `18802e9d8a8baf1fd92ec5bc53fbe41b5e429e8239ee687dde1283a78aba6831`.
- SHA-256 do APK: `3fcf8e66bf57333a5a585d3f2a45c04cf02cbe5e5aac68c667b037bd9c07841f`.
- Log da geração: `../../work/apk-1.2.0-build.log`.

Essa etapa não publicou a versão nem alterou o Firebase. Os testes automatizados não confirmam que os índices compostos foram criados no banco da loja; confira essa configuração conforme [INSTALAR-1.2.0.md](INSTALAR-1.2.0.md).

## Cobertura relevante

- Custo de caixa exato e custo avulso arredondado; custo histórico preservado nas vendas.
- Venda dividida, troco, cliente e fiado; limite com liberação administrativa justificada.
- CMV proporcional de parcelas e ajuste dos recebimentos em estornos.
- Abertura/fechamento de caixa, revisões concorrentes, sangrias, suprimentos e estornos completos.
- Regras com oito produtos, todos os vínculos do lote, fiado parcialmente recebido ou quitado, tentativa de estorno repetido e perfis desativados.
- Relatórios e fiado consultados somente nas telas correspondentes; cancelamento das consultas ao sair e recuperação depois de erro.
- Confirmação de sincronização após sair do fiado, sem bloquear indefinidamente a troca de conta.
- Cento e sessenta vendas consecutivas sem consumidor de mensagens, verificando que a fila de avisos não trava o atendimento.
- Mudança de permissões sem perder o carrinho, restauração de acesso validado e troca de conta com conferência de pendências.
- Arquivo de atualização verificado, recuperação do estado baixado e conservação após cancelamento ou solicitação de permissão de instalação.
- Consulta de pagamentos por sessão sob demanda, tratamento de timeout e descarte de resultados após fechar a tela.
- Exportação CSV, proteção de campos de texto contra fórmulas e preservação de telefones/códigos.
- Migração idempotente, conciliação de saldos/parcelas, ordem dos pagamentos e rejeição de históricos inconsistentes.

## Limites desta validação

Não foram realizados testes instrumentados ou testes manuais nos celulares. Câmera, navegação e teclado em aparelhos reais, exportação pelo seletor Android, dois celulares desconectados e o instalador do Android ainda precisam da conferência descrita em [ATUALIZACAO-1.2.0.md](ATUALIZACAO-1.2.0.md).

Na validação inicial, nenhum APK/AAB foi gerado. A autorização posterior para gerar o APK não inclui distribuição ou alterações no Firebase. A migração e o primeiro administrador foram preparados em scripts; não foram aplicados ao banco da loja. As regras e os índices também não foram publicados. A pasta de atualização usada como backup foi preservada.

Antes da futura instalação, siga [o roteiro da atualização](ATUALIZACAO-1.2.0.md), [a preparação do histórico](tools/admin/MIGRACAO-V3.md) e [a configuração de perfis](tools/admin/PERFIS.md). Não misture a operação da versão antiga com a nova após migrar os dados.

## Evidências locais

- Compilação, testes e Lint: `../../work/v3-final-validation.log`.
- Regras do Firestore: `../../work/security-validation.log`.
- Relatórios JVM: `core/build/reports/tests/test/index.html` e `app/build/reports/tests/testDebugUnitTest/index.html`.
- Análise Android: `app/build/reports/lint-results-debug.html`.
- Testes da migração: `tools/admin/migration-v3-planner.test.mjs`.
