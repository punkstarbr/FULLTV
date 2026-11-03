# Publicação manual

O FullTV **não publica releases por GitHub Actions**. O workflow privilegiado de release foi removido; os workflows restantes fazem testes/análise ou publicam a documentação, sem permissão para anexar APKs a uma release.

## Preparar a release

1. Atualize `versionCode` e `versionName` em `app/build.gradle.kts`; o `versionCode` deve aumentar em toda versão.
2. Use JDK 17, Android SDK compatível e Python 3.11 para o Chaquopy.
3. Rode os testes Python isolados e, somente depois, os testes/builds Android relevantes:

   ```bash
   python3 -m unittest discover -s tools -p 'test_*.py'
   ./gradlew --no-daemon clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
   ```

4. O `assembleRelease` exige `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` e `RELEASE_CERT_SHA256` (64 hex dígitos). A tarefa falha fechada antes de empacotar se qualquer entrada de produção faltar; nunca há fallback para o keystore debug. Não coloque keystore, senhas ou tokens no repositório, em logs ou em uma pull request.
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

A release pública `v0.11.22` foi assinada com a chave debug do ambiente de build porque não havia keystore de produção. A opção de repetir esse processo foi removida: não existe build release confiável sem o keystore/certificado de produção e os segredos fornecidos pelo proprietário. Não será possível montar um release instalável confiável nesta cópia sem esses segredos; não crie nem invente uma chave para contornar o bloqueio.

Para uma release real, forneça as variáveis de ambiente por um gerenciador de segredos ou shell privado, nunca por argumentos da linha de comando que possam aparecer no histórico:

```bash
export KEYSTORE_PATH=/caminho/fora-do-repositorio/fulltv-release.jks
export KEYSTORE_PASSWORD='...'
export KEY_ALIAS='...'
export KEY_PASSWORD='...'
export RELEASE_CERT_SHA256='fingerprint-SHA256-em-64-hex'
```

O fingerprint deve ser o certificado público do alias de produção e será incorporado ao APK para que o atualizador aceite somente a mesma âncora. A antiga release assinada com debug não é uma âncora de produção aceitável para builds novos; instalações baseadas nela podem exigir migração manual/reinstalação, com risco de perda dos dados locais. Planeje e comunique essa transição antes de publicar.

O APK `debug` usa outro `applicationId` (`app.fulltv.debug`) e pode coexistir com o app release. Nunca o publique como atualização do app release.

## Wheelhouse Chaquopy

Antes de cada instalação Chaquopy, `verifyChaquopyWheelHashes` limpa e recria `app/build/chaquopy-wheelhouse/`, baixa as wheels travadas para as quatro ABIs, verifica seus bytes com SHA-256 e grava um manifesto. As tarefas Chaquopy reutilizam exatamente essa pasta com `--no-index`, `--find-links` e `--require-hashes`; não há segundo download online durante o empacotamento. Se a verificação falhar, não force a instalação.

O Streamlink 8.6.3 ainda declara `lxml`; por isso o APK mantém `lxml==5.3.0` enquanto o índice Android oficial não oferece uma wheel cp311 corrigida para as quatro ABIs. O guard `fulltv_lxml_guard.py` e os testes XXE permanecem obrigatórios. Quando a wheel corrigida existir, atualize a versão e todos os hashes em mudança revisada; não silencie o advisory nem invente uma versão.

## Integridade do Gradle Wrapper

O repositório fixa SHA-256 da distribuição Gradle 8.13 em `gradle-wrapper.properties` e mantém o hash oficial do Wrapper JAR em `gradle/wrapper/gradle-wrapper.jar.sha256`. O CI valida esse JAR antes de executar Gradle; confira ambos novamente ao atualizar o Wrapper.
