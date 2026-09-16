# Coloque suas fotos nesta pasta

Esta é a pasta de imagens do Hora da Gelada PDV. Copie aqui as fotos dos produtos antes de gerar uma atualização do aplicativo.

1. Use arquivos **JPG, JPEG ou PNG**. Converta fotos HEIC do celular para JPG antes de copiar.
2. Dê um nome fácil de buscar, por exemplo `cerveja-lata-350ml.jpg` ou `refrigerante-cola-2l.png`. Esses exemplos são nomes; nenhuma foto de produto vem cadastrada neste projeto.
3. Gere o APK normalmente no Android Studio. A preparação das imagens acontece automaticamente: cópias com no máximo 512 pixels no maior lado, JPEG a 82% de qualidade, proporção preservada e sem os metadados originais. O arquivo original nesta pasta permanece intacto. A orientação EXIF das fotos JPEG é aplicada à cópia. Transparências ficam sobre fundo branco.
4. Distribua a nova versão pelo Firebase App Distribution, como explica [ATUALIZACOES.md](../ATUALIZACOES.md), e atualize os dois celulares.
5. No aplicativo, abra **Estoque → Cadastrar produto** ou **Editar → Escolher foto**. Busque pelo nome do arquivo, toque na foto e salve o produto. Ela aparece no estoque, no caixa e no carrinho, inclusive sem internet.

Você pode acrescentar fotos sempre que preparar uma atualização. Só o nome identificador da foto fica no Firestore; os arquivos ficam no aplicativo. Não há Firebase Storage, envio de arquivos nem necessidade de plano Blaze para estas imagens.

**Depois de usar uma foto, mantenha seu nome.** O identificador vem do nome sem extensão, em minúsculas, sem acentos e com espaços substituídos por hífens. Por exemplo, `Cerveja Lata 350ml.jpg` vira `cerveja-lata-350ml`. Renomear ou excluir uma imagem já usada deixa os produtos correspondentes sem a foto nessa versão, mas mantém seus dados e o identificador para recuperá-la em outra atualização.

Nomes equivalentes não podem coexistir: `Cerveja.png` e `cerveja.jpg` geram o mesmo identificador e a compilação explica quais arquivos precisam ser renomeados. Use nomes de até 100 caracteres, sem contar a extensão. Subpastas são aceitas, mas os nomes ainda precisam ser únicos.

Para trocar apenas a imagem de um produto já associado, substitua o arquivo mantendo o mesmo nome. Os celulares passam a mostrar a imagem nova depois de instalar a atualização. Um celular com versão antiga continua funcionando e mostra um espaço reservado para fotos que ainda não possui.

As cópias prontas para o APK são geradas em `app/build/generated/productPhotos/assets/product_photos/`. Não edite essa pasta; ela é recriada pela compilação.
