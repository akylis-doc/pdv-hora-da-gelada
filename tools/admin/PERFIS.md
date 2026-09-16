# Configuração do primeiro administrador

O aplicativo permite cadastrar operadores em **☰ → Perfis da equipe**, a partir de uma conta de administrador. A primeira conta de administrador precisa ser definida uma única vez por quem controla o projeto Firebase. O aplicativo não promove automaticamente a primeira pessoa que entra.

1. No Firebase Console, abra **Authentication → Usuários**. Localize sua conta atual e copie o **UID**. Se ainda não existir uma conta, crie uma com e-mail e senha nesta página.
2. Confira o ID do projeto em **Configurações do projeto → Geral**.
3. Em um computador confiável, configure as credenciais administrativas do projeto com Application Default Credentials. Se utilizar um arquivo de conta de serviço, mantenha-o fora da pasta do projeto, das fotos, do APK e de qualquer pasta compartilhada. A variável `GOOGLE_APPLICATION_CREDENTIALS` pode apontar para esse arquivo particular.
4. Abra um terminal na pasta `tools/admin` e instale as dependências com `npm ci`, se ainda não estiverem instaladas.
5. Confira os dados, substituindo os três exemplos:

   ```powershell
   node bootstrap-admin.mjs ID_DO_PROJETO UID_DA_SUA_CONTA "Seu nome"
   ```

   Este comando apenas confere a conta. Ele não imprime senhas nem credenciais e não modifica dados.

6. Para registrar o administrador escolhido, execute o mesmo comando com `--apply`:

   ```powershell
   node bootstrap-admin.mjs ID_DO_PROJETO UID_DA_SUA_CONTA "Seu nome" --apply
   ```

7. As regras da nova versão precisam estar publicadas pelo responsável pela loja antes de usar os perfis. Este script não publica regras, não envia convites e não gera instalação.
8. Abra o aplicativo conectado. Se aparecer a tela de acesso pendente, toque em **Conferir acesso novamente**. Depois, abra **☰ → Perfis da equipe → Cadastrar operador**.

O script recusa a criação do primeiro administrador quando já existe outro administrador ativo. Para promover mais administradores, use **Editar operador** dentro do aplicativo. Executar novamente para o mesmo administrador já ativo não altera seu perfil.

## Como cadastrar e manter os operadores

Informe nome, e-mail, senha inicial e permissões. A conta de quem está cadastrando permanece conectada. Entregue a senha diretamente à pessoa por um meio privado; ela não é salva no Firestore nem no histórico de recuperação do aplicativo.

Se a conta for criada no Authentication, mas o perfil não for confirmado no banco, aparecerá **Cadastro incompleto → Concluir cadastro**. Use essa opção: ela reutiliza o UID já criado. Se o aplicativo fechar antes de guardar essa pendência, repita o cadastro com o mesmo e-mail e senha para recuperar a conta existente.

Em **Editar**, é possível alterar nome, permissões e ativar/desativar o acesso. A própria conta de administrador não pode ser desativada nem rebaixada por ela mesma. Vendas anteriores continuam vinculadas ao UID original.

O primeiro acesso de uma conta neste celular precisa de internet. Depois, uma sessão anteriormente validada pode usar o caixa offline. A desativação e alterações de permissão chegam ao aparelho quando ele reconecta; as regras do servidor impedem novas gravações sem a autorização atual. Um perfil desativado tem prioridade sobre a antiga autorização `pdv`.

As permissões controlam ações e a abertura de relatórios. O catálogo usado para vender offline contém o custo dos produtos para congelar o custo histórico de cada venda; portanto, a permissão de relatórios não representa sigilo desses campos no cache do dispositivo.
