# Publicação manual

O FullTV **não publica releases por GitHub Actions**. O workflow privilegiado de release foi removido; os workflows restantes fazem testes/análise ou publicam a documentação, sem permissão para anexar APKs a uma release.

## Preparar a release

1. Atualize `versionCode` e `versionName` em `app/build.gradle.kts`; o `versionCode` deve aumentar em toda versão.
2. Use JDK 17, Android SDK compatível e Python 3.11 para o Chaquopy.
3. Rode os testes relevantes e faça uma build limpa dos dois APKs:

   ```bash
   ./gradlew --no-daemon clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
   ```

4. O `assembleRelease` exige `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. A tarefa falha antes de empacotar se a assinatura não estiver configurada. Não coloque keystore, senhas ou tokens no repositório, em logs ou em uma pull request.
5. Verifique package, versão, SDK mínimo, ABIs e certificado com `apkanalyzer`/`apksigner`. Compare o SHA-256 do certificado com a última release que os usuários instalaram. Se o certificado mudar, o Android não atualizará por cima da instalação atual.
6. Copie os APKs para uma pasta vazia e gere `SHA256SUMS.txt`. Publique manualmente usando os nomes canônicos `FullTV-<versão>-universal.apk` e `FullTV-<versão>-debug.apk`:

   ```bash
   sha256sum FullTV-<versão>-universal.apk FullTV-<versão>-debug.apk > SHA256SUMS.txt
   gh release create v<versão> \
     FullTV-<versão>-universal.apk \
     FullTV-<versão>-debug.apk \
     SHA256SUMS.txt \
     --repo punkstarbr/FULLTV \
     --title "FullTV v<versão>" \
     --notes-file release-notes.md
   ```

Não reutilize uma tag existente nem publique um APK sem validar o certificado e os hashes. O atualizador exige asset canônico, digest SHA-256, package/version esperados e certificado igual ao app instalado.

## Assinatura e compatibilidade

A release pública `v0.11.22` foi assinada com a chave debug do ambiente de build porque não havia keystore de produção. O código agora **não usa essa chave automaticamente**. Para build release, forneça um keystore e segredos por variáveis de ambiente, nunca por argumentos da linha de comando que possam aparecer no histórico.

Para preservar a compatibilidade de atualização das instalações `v0.11.22`, qualquer build de transição precisa usar exatamente o mesmo certificado da release já publicada. `FULLTV_ALLOW_DEBUG_SIGNED_RELEASE=true` é uma exceção explícita e insegura, destinada apenas a manter o certificado legado no ambiente que possui aquela chave; não a use como solução de produção nem em outro computador. A chave privada não está no repositório. Sem ela, uma nova assinatura exigirá desinstalar/reinstalar o app e poderá apagar os dados locais. Planeje e comunique essa transição antes de publicar.

O APK `debug` usa outro `applicationId` (`app.fulltv.debug`) e pode coexistir com o app release. Nunca o publique como atualização do app release.

## Integridade do Gradle Wrapper

O repositório fixa SHA-256 da distribuição Gradle 8.13 em `gradle-wrapper.properties` e mantém o hash oficial do Wrapper JAR em `gradle/wrapper/gradle-wrapper.jar.sha256`. O CI valida esse JAR antes de executar Gradle; confira ambos novamente ao atualizar o Wrapper.
