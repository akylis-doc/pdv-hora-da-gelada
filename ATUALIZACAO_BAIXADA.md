# Download e instalação separados

A tela **Atualizações** agora distingue duas ações:

1. **Baixar atualização** guarda uma cópia completa da versão disponibilizada para a conta de testes.
2. **Instalar agora** abre a confirmação de instalação do Android. Se o Android pedir permissão, ative **Permitir desta fonte**, volte ao aplicativo e toque novamente em **Instalar agora**.

Cancelar a tela de instalação não apaga um download concluído. Ao abrir novamente o aplicativo ou a tela de atualizações, ele confere o arquivo guardado e permite retomar. Depois que a versão for instalada, o arquivo pendente é descartado na próxima conferência.

O caixa precisa estar sem carrinho e com as gravações sincronizadas para iniciar essas etapas. Um download muito demorado libera o caixa após dez minutos. O SDK pode continuar a transferência; volte a Atualizações para conferir. Ele não abrirá a instalação por conta própria. Fechar o processo durante uma transferência incompleta pode exigir baixar novamente.

## Manutenção técnica

A implementação de testes está vinculada ao Firebase App Distribution **16.0.0-beta20**. Essa versão oferece `updateApp()` para baixar e iniciar a instalação, mas não expõe uma operação pública para apenas baixar. O adaptador mantém a autenticação e a transferência no SDK, desativa sua `com.google.firebase.appdistribution.impl.InstallActivity` somente no manifesto `debug` e usa um instalador explícito após a verificação local.

O fluxo interno foi conferido no bytecode do SDK: `ApkUpdater` entrega o arquivo a `ApkInstaller` por uma continuação `onSuccessTask`; falhar ao abrir a atividade desativada encerra a tarefa pelo caminho de erro. O evento `DOWNLOADED` permite guardar o arquivo antes dessa tentativa automática. Não há reflexão, leitura de tokens nem reaproveitamento de URLs autenticadas.

A busca está limitada aos dois nomes usados pelo SDK, dentro de `Context.filesDir`: `<rótulo atual do app>.apk` e `downloaded_release.apk`. O adaptador não procura arquivos no armazenamento externo. Uma cópia só fica pronta quando corresponde ao pacote instalado, ao código da versão esperada e ao conjunto completo de certificados da instalação atual. As entradas ZIP são lidas com conferência de tamanho e CRC. A cópia é guardada em `filesDir/verified_updates` com SHA-256 para conferir mudanças posteriores. O instalador do Android faz sua própria verificação final do pacote.

O `FileProvider` de atualização concede leitura temporária apenas à pasta `verified_updates`. A variante `release` continua sem o SDK de atualização de testes. Mudanças de chave de assinatura não são aceitas automaticamente por este adaptador.

Ao alterar a versão do SDK, revise os nomes privados dos arquivos, a atividade desativada e o caminho de conclusão da tarefa. São detalhes internos e podem mudar entre versões.

## Conferência nos celulares antes da distribuição oficial

Os testes automatizados verificam identidade de pacote/versão/assinatura e os bloqueios de instalação ligados ao caixa. A abertura do instalador depende do Android e precisa ser conferida nos dois aparelhos:

- Baixar uma versão de teste já disponibilizada, confirmar que aparece **Instalar agora** e que o caixa volta a responder.
- Fechar e reabrir o aplicativo com o download concluído; o botão deve continuar disponível sem nova transferência.
- Recusar a permissão de instalação, voltar, permitir e tentar novamente.
- Abrir o instalador, cancelar e retomar pelo mesmo botão.
- Confirmar a instalação sobre o app atual e conferir versão, conta conectada e registros da loja.
- Interromper a rede durante uma transferência e verificar a mensagem de recuperação, sem aceitar um arquivo parcial.
- Deixar uma venda pendente ou um carrinho aberto e confirmar que a instalação aguarda a conclusão.

Este documento descreve a implementação e a conferência posterior. Nenhum APK/AAB foi gerado, nenhuma atualização foi enviada e nenhuma regra do Firebase foi publicada nesta etapa.
