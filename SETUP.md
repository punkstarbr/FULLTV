# Configuração do FullTV

Este projeto é um fork do [OpenTV](https://github.com/opentvproject/opentv). As instruções
originais de publicação apontavam para o repositório e a página do upstream; **não use este
projeto para fazer push no OpenTV original**.

Para publicar o FullTV no GitHub, siga o [PUBLICAR.md](PUBLICAR.md).

## Compilar localmente

- JDK 17
- Android SDK 35 e build-tools 35.0.0
- Python 3.11 (usado pelo Chaquopy para empacotar Streamlink e yt-dlp)

Streamlink, yt-dlp e o runtime Python são incluídos no APK; não é necessário instalar outro app,
runtime adicional ou servidor. Para buscar lives, adicione uma URL pública `youtube.com/results`
como fonte YouTube Live Search; o app consulta os metadados diretamente no YouTube e mostra até
cinco resultados ativos. O runtime atual do Chaquopy eleva o mínimo do FullTV para Android 7.0 (API 24).
O APK universal inclui as ABIs `arm64-v8a`, `armeabi-v7a`, `x86` e `x86_64`, o que aumenta o
tamanho do arquivo.

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
