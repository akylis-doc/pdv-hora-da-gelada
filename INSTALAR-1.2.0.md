# Instalar o PDV Hora da Gelada 1.2.0

O [APK **1.2.0 (5)**](../PDV-Hora-da-Gelada-1.2.0.apk) foi gerado em 11/09/2026, com versão, nome e assinatura conferidos. Ele pertence ao canal de testes. A conferência prática nos dois celulares ainda precisa ser concluída.

## Antes de instalar e voltar a atender

1. **Pare o atendimento nos dois celulares.** Conecte ambos e espere todas as operações serem sincronizadas. Confira pendências e esvazie os carrinhos.
2. **Prepare o histórico da loja no computador**, seguindo [MIGRACAO-V3.md](tools/admin/MIGRACAO-V3.md): confira os dados, aplique a preparação e confirme que não restam alterações necessárias.
3. **Defina o primeiro administrador**, seguindo [PERFIS.md](tools/admin/PERFIS.md).
4. **Publique as regras e os índices desta versão**, conforme o passo 6 de [ATUALIZACAO-1.2.0.md](ATUALIZACAO-1.2.0.md). Aguarde a conclusão antes de operar.
5. **Instale a mesma versão nos dois celulares, sobre o aplicativo existente.** Abra o APK no celular e confirme a atualização. Se o Android pedir permissão para instalar por esse aplicativo, permita e volte ao instalador. Não desinstale nem limpe os dados do PDV.
6. **Entre conectado e confira os dados.** Verifique versão, conta, produtos, clientes e fiados. Abra uma sessão em **☰ → Controle de caixa** em cada aparelho e faça os testes indicados no [roteiro da atualização](ATUALIZACAO-1.2.0.md).

Depois de preparar o banco, mantenha os aparelhos parados até ambos estarem atualizados. Não misture o atendimento da versão antiga com a 1.2.0.

O APK deve conservar o identificador e a assinatura da instalação atual para atualizar sem desinstalar. A correção do botão **Instalar agora** passa a funcionar a partir da 1.2.0; se o aplicativo antigo não abrir o instalador, abra diretamente o arquivo recebido. A distribuição pelo Firebase App Distribution continua documentada em [ATUALIZACOES.md](ATUALIZACOES.md).

**Gerar o APK não prepara o banco:** nenhuma migração, configuração de administrador, publicação de regras/índices ou distribuição ocorre automaticamente.

## Erro com link para criar índice ao abrir Controle de caixa

Esse aviso indica um índice ausente ou ainda em construção no Firestore. Não exige gerar outro APK. Publicar somente o conteúdo de `firestore.rules` na aba **Regras** não cria os índices.

1. Abra o projeto do aplicativo no [Console do Firebase](https://console.firebase.google.com/).
2. Acesse **Firestore Database → Índices → Índices compostos → Adicionar índice**.
3. Crie os dois índices abaixo, com escopo **Coleção**. Use exatamente as letras maiúsculas e minúsculas indicadas. Se um deles já existir, confira o status em vez de duplicá-lo.

| ID da coleção | Primeiro campo | Ordem | Segundo campo | Ordem |
| --- | --- | --- | --- | --- |
| `CaixaSessoes` | `aparelho_id` | Crescente | `dataHora` | Decrescente |
| `MovimentosCaixa` | `aparelho_id` | Crescente | `dataHora` | Decrescente |

4. Aguarde os dois índices terminarem de ser criados e ficarem ativados. Isso pode levar alguns minutos.
5. Feche completamente o PDV e abra novamente, conectado à internet. Entre em **☰ → Controle de caixa**. Não desinstale nem limpe os dados.

O link apresentado no erro também abre a criação com os campos preenchidos: revise e clique em **Criar**. Ele pode se referir a apenas um dos índices, por isso confira os dois na lista. Esse procedimento é descrito na [documentação do Firebase](https://firebase.google.com/docs/firestore/query-data/indexing?hl=pt-br).

As definições já estão em [firestore.indexes.json](firestore.indexes.json), que também pode ser publicado pela Firebase CLI conforme o roteiro da atualização. Não cole esse JSON no editor de **Regras**.
