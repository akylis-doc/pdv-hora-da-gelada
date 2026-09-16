# Atualizar o Hora da Gelada PDV — comece aqui

Este guia é para a loja que já está usando o aplicativo. A versão atual é **1.1.1 (4)**, com atualizações em **Ajustes → Atualizações**. Ela muda apenas a marca de Gela PDV para **Hora da Gelada PDV**, preservando todas as funções da 1.1.0. Você continua usando o mesmo projeto Firebase, banco e contas. As regras do Firestore são as mesmas da 1.1.0: quem já as publicou não precisa republicar apenas pela mudança de nome; quem vem de uma versão anterior ainda precisa publicá-las. Siga [NOVAS-FUNCOES.md](NOVAS-FUNCOES.md) para conferir essa etapa e conhecer a pasta das fotos.

O App Distribution é gratuito para distribuir versões de teste. Não é necessário contratar Blaze para este fluxo nem ativar o Storage. [Fonte: Firebase App Distribution](https://firebase.google.com/products/app-distribution).

## 1. Entenda o que acontecerá

Você envia uma versão para o Firebase pelo computador. Os celulares autorizados podem baixá-la pela internet. O operador confirma a instalação no Android. Não é uma instalação silenciosa.

Se o aparelho ainda usa a versão 1.0.0, faça a primeira atualização pelo convite do Firebase ou abrindo o APK no celular. Na 1.0.1, há uma aba Atualizações; a partir da 1.1.0, ela fica em Ajustes. As próximas versões podem ser consultadas dentro do Hora da Gelada PDV.

## 2. Guarde a chave que já está sendo usada

Neste computador, a chave do APK anterior está em:

```text
C:\Users\akylis\.android\debug.keystore
```

Guarde uma cópia particular desse arquivo em local seguro, fora da pasta compartilhada do projeto. Não o envie aos operadores. Ele permite assinar as próximas versões compatíveis com a instalação atual.

O projeto verifica o certificado dessa chave antes de compilar a versão de testes. Se aparecer “Assinatura diferente”, restaure a chave original. Não altere o certificado esperado para contornar esse aviso. A chave é de testes e foi mantida para preservar a compatibilidade nesta fase; a assinatura da versão oficial deverá ser planejada antes da distribuição definitiva.

**Não desinstale o aplicativo nem limpe seus dados para atualizar.** Ele pode aparecer com o nome anterior, Gela PDV, até receber esta atualização. Uma atualização compatível mantém o armazenamento e a sessão do app; desinstalar pode apagar registros ainda locais. Antes da primeira atualização, termine o atendimento e confira a sincronização nos dois celulares.

## 3. Ative o canal no Firebase pelo computador

1. Abra o [console Firebase](https://console.firebase.google.com/) e entre na conta que administra o projeto da loja.
2. Abra o mesmo projeto usado pelo aplicativo. Não crie um banco novo.
3. Localize **App Distribution** no menu do projeto. Se necessário, expanda os produtos. Clique em **Começar**.
4. Selecione o aplicativo Android cujo identificador é `br.com.bebidaspdv`.
5. Abra a [API Firebase App Testers](https://console.cloud.google.com/apis/library/firebaseapptesters.googleapis.com), selecione o mesmo projeto no topo e clique em **Ativar**. Se já estiver ativada, prossiga.

Essa API permite a consulta de versões pelo aplicativo. [Configuração oficial do SDK](https://firebase.google.com/docs/app-distribution/set-up-alerts?platform=android).

## 4. Envie a versão atual

O arquivo pronto entregue nesta atualização se chama **Hora-da-Gelada-PDV-1.1.1-testes.apk**. Use o link do APK na resposta desta tarefa. Ele também estará na pasta `outputs/Atualizacoes/1.1.1-4` ao lado do projeto.

1. No App Distribution, abra **Versões / Releases** e envie esse APK.
2. Informe os e-mails Google que serão usados para receber as versões nos celulares. Você pode criar um grupo chamado `loja-testes` para reutilizar a lista.
3. Cole o conteúdo do arquivo `novidades.txt`, que acompanha o APK, nas notas da versão.
4. Confira o aplicativo, a versão **1.1.1 (4)** e os destinatários. Clique em **Distribuir**.

O Firebase enviará os convites. Enviar somente o arquivo, sem distribuir aos testadores, não libera a versão para eles. As versões ficam disponíveis para download por 150 dias; mantenha também seus APKs arquivados. [Distribuição pelo console](https://firebase.google.com/docs/app-distribution/android/distribute-console).

## 5. Prepare cada celular

1. Conecte o celular à internet, finalize o atendimento e aguarde a sincronização do aplicativo.
2. Abra o e-mail de convite do Firebase no celular. Confira também a pasta de spam.
3. Aceite o convite e entre com a conta Google convidada. Siga a página de testes para baixar o APK; ela pode oferecer o aplicativo Firebase App Tester.
4. Abra o APK. Se o Android pedir, autorize a instalação para o aplicativo que está abrindo o arquivo. Os nomes das telas variam entre aparelhos.
5. Confirme **Atualizar / Instalar** sobre o aplicativo existente, antes chamado Gela PDV.
6. Abra o Hora da Gelada PDV e confira os produtos e o acesso à loja. Em **Ajustes → Atualizações**, deve aparecer **1.1.1 (4)**.
7. Toque em **Buscar atualização** e, quando solicitado, entre com a conta Google de testes.

Essa conta de testes é separada da conta do operador do caixa. O login já salvo da loja continua sendo usado. [Orientações do Firebase para testadores](https://firebase.google.com/docs/app-distribution/get-set-up-as-a-tester).

Se você já instalou a versão mais recente liberada para sua conta, “Nenhuma versão mais recente disponível” é esperado. O teste de download dentro do app exige que exista uma versão distribuída com código maior que a instalada.

## 6. Envie as próximas melhorias

Para cada nova entrega, faremos as alterações, testes e um novo APK. Por exemplo, depois da **1.1.1 (4)**, a próxima poderá ser **1.1.2 (5)**. O número entre parênteses é o código interno: sempre deve aumentar.

No computador:

1. Receba o novo APK testado e suas notas de versão.
2. Abra o mesmo App Distribution, selecione o mesmo aplicativo e envie o novo arquivo.
3. Primeiro libere para o responsável pelos testes. Confira acesso, catálogo, estoque e vendas antes de liberar para os dois caixas.
4. Distribua a versão para os demais testadores autorizados.

No celular:

1. Abra **Ajustes → Atualizações → Buscar atualização**.
2. Leia as novidades da versão disponível.
3. Finalize a venda em andamento e aguarde a sincronização. O botão de instalação fica indisponível se houver carrinho ou gravações pendentes.
4. Toque em **Baixar e atualizar**, confirme e aguarde. O caixa fica pausado durante essa etapa.
5. Siga a confirmação de instalação do Android e reabra o app.

Não há consulta automática que interrompa uma venda. O Firebase avisa por e-mail quando você distribui uma versão; dentro do Hora da Gelada PDV, a consulta é feita pelo botão.

## 7. Se você mesmo quiser gerar o próximo APK

Abra a pasta **BebidasPDV** no Android Studio. Mantenha o `app/google-services.json` atual e a chave original. Configure o JDK 17 e o SDK Android 35.

Após alterar e revisar o código:

1. Em `app/build.gradle.kts`, aumente `versionCode` e atualize `versionName`. Exemplo: `5` e `"1.1.2"`.
2. Crie `distribution/notes-1.1.2.txt` descrevendo as mudanças.
3. No terminal PowerShell do Android Studio, aberto na pasta BebidasPDV, execute:

```powershell
.\tools\distribution\Prepare-Update.ps1 -PreviousVersionCode 4
```

Use em `PreviousVersionCode` o maior código que você já distribuiu, não necessariamente o do seu próprio celular. O script compila, executa testes, verifica assinatura e separa o APK com um nome claro. Ele não publica nem envia convites.

No exemplo, os arquivos ficam em:

```text
BebidasPDV\distribution\build\1.1.2-5\
    Hora-da-Gelada-PDV-1.1.2-testes.apk
    novidades.txt
    verificacao.json
```

Envie apenas o APK ao App Distribution e copie as notas. Não envie o ZIP do projeto, `google-services.json`, a chave ou a pasta inteira. Se o PowerShell bloquear scripts, use a compilação normal do Android Studio com a variante **debug** e localize `app/build/outputs/apk/debug/app-debug.apk`; a checagem da chave também roda nessa compilação.

## 8. Se algo não funcionar

| Situação | Como resolver |
| --- | --- |
| Não encontro Atualizações | A partir da 1.1.0, abra Ajustes. Na 1.0.0, atualize primeiro pelo convite ou APK entregue. |
| Não aparece uma nova versão | Confira se ela foi distribuída ao e-mail usado no celular e se seu código é maior que o instalado. |
| Erro ao consultar | Confira internet, convite aceito e API Firebase App Testers ativada. Se usou a conta Google errada, toque em Trocar conta de testes e consulte novamente; isso não desconecta o caixa. |
| Botão de atualização bloqueado | Termine o atendimento e confira as pendências em Sincronização. |
| Android recusou a instalação | Confira espaço livre, permissão para instalar e assinatura do APK. Não desinstale o app como tentativa de solução. |
| Cancelou o download ou a instalação | Volte a Ajustes → Atualizações e consulte novamente quando a operação atual encerrar. |
| Uma melhoria apresentou defeito | Suspenda sua distribuição e prepare uma correção com código maior. Não tente instalar um APK mais antigo por cima nem apague dados. |

## Para quem mantém o código

- O SDK completo do App Distribution está somente em `debugImplementation`. A variante `release` usa `UnavailableUpdates` e não inclui o SDK de atualização. A distribuição atual é privada, para testes.
- A versão 1.1.0 acrescentou campos e coleções e exige publicar as regras atualizadas ao migrar de versões anteriores. A 1.1.1 muda apenas a marca e usa as mesmas regras; quem já as publicou não precisa republicar. Ela continua lendo registros antigos e preserva a chave e o identificador do app. Atualize os dois aparelhos antes de começar a usar as funções da 1.1.0, conforme NOVAS-FUNCOES.md.
- A instalação usa a trava das operações do PDV, exige carrinho vazio e verifica a fila completa do Firestore com limite de 15 segundos antes de chamar o SDK. O download usa progresso dentro da tela.
- Versões futuras precisam continuar lendo registros antigos e tolerar campos novos ausentes. Os dois celulares podem ficar em versões diferentes; uma mudança de banco exige planejamento próprio. Distribuição remota não elimina a necessidade de testar cada alteração.
- Para validar a atualização real, instale a 1.0.1 em um aparelho de teste, distribua uma versão seguinte, teste aceite/cancelamento no Android e confira a sessão e os dados após atualizar. Testes locais não substituem essa validação com a conta de testador.

