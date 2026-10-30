# Configuração do FullTV

Este projeto é um fork do [OpenTV](https://github.com/opentvproject/opentv). As instruções
originais de publicação apontavam para o repositório e a página do upstream; **não use este
projeto para fazer push no OpenTV original**.

Para publicar o FullTV no GitHub, siga o [PUBLICAR.md](PUBLICAR.md).

## Compilar localmente

- JDK 17
- Android SDK 35 e build-tools 35.0.0

```bash
export JAVA_HOME=/caminho/para/jdk17
export ANDROID_HOME=/caminho/para/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

./gradlew :app:testDebugUnitTest :app:assembleDebug --no-configuration-cache
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

## Assinatura

O build de release usa a chave de debug quando nenhum keystore é configurado. Para publicar
de verdade, configure `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD` e
leia `docs/RELEASING.md`. Trocar a assinatura depois exige desinstalar e reinstalar o app.
