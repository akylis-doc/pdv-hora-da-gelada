# Instalar a versão 1.3.0 (6)

O nome previsto para o arquivo de entrega é **PDV-Hora-da-Gelada-1.3.0.apk**, na pasta `outputs`, ao lado da pasta do projeto `BebidasPDV`. Consulte [VALIDACAO-1.3.0.md](VALIDACAO-1.3.0.md) antes de instalar: a geração desta versão ainda depende de concluir a compilação bloqueada por acesso a arquivos no ambiente automatizado. O repositório GitHub armazena o código; o instalador é um artefato separado.

1. Nos celulares, confira se as operações anteriores sincronizaram e finalize qualquer carrinho aberto.
2. Copie o APK para o celular e toque nele. Se solicitado, autorize a instalação pela origem que abriu o arquivo.
3. Escolha **Atualizar**. Não desinstale o aplicativo nem apague seus dados. O identificador continua `br.com.bebidaspdv`; a compilação de testes exige a chave original e verifica sua impressão digital antes de gerar o APK.
4. Abra o aplicativo com a conta já conectada. Como administrador, vá a **☰ → Ajustes → Diagnóstico → Abrir Logs**.
5. Faça uma venda de conferência e os testes práticos indicados em [DIAGNOSTICO.md](DIAGNOSTICO.md). Confira também o leitor com etiquetas reais.
6. Atualize o segundo celular e confira a sincronização entre ambos antes do uso normal na loja.

Se o Android disser que a assinatura é incompatível, interrompa a instalação e confira a origem do APK já instalado. Não desinstale para contornar o erro.

Para quem já usa a 1.2.0 com as regras/índices publicados, **não há migração ou alteração de regras exigida pelos logs**. O histórico técnico fica somente no aparelho. Quem vem de versões mais antigas ainda deve seguir a preparação da [1.2.0](ATUALIZACAO-1.2.0.md).

Para distribuir pelo Firebase App Distribution, use o procedimento de [ATUALIZACOES.md](ATUALIZACOES.md) e as notas em `distribution/notes-1.3.0.txt`. Gerar o APK e publicar o código no GitHub não distribui automaticamente o instalador aos celulares.
