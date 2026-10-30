# Publicar o FullTV no GitHub

Este documento é para quem vai subir o projeto no GitHub. O zip `FullTV-github.zip` contém:

```
fulltv/                          # projeto completo (sem .git, build/ ou caches)
FullTV-0.11.10-debug.apk         # APK pronto para anexar na Release
opentv-modificacoes.patch        # diff completo contra o OpenTV original
LEIA-ME.txt                      # resumo rápido
```

O APK fica **fora** da pasta do repositório de propósito: binários não devem entrar no commit,
mas devem ser anexados a uma Release.

## 1. Preparar a pasta

```bash
unzip FullTV-github.zip
cd fulltv
```

Se o zip não tiver `.git`, inicialize o histórico:

```bash
git init -b main
git add .
git commit -m "FullTV: fork do OpenTV com tema laranja, pt-BR, EPG automático e gravação MP4"
```

Não faça push no repositório do OpenTV original. O remoto deve ser um repositório novo e
público, por exemplo `SEU-USUARIO/fulltv`.

## 2. Criar o repositório e enviar

Com o GitHub CLI autenticado (`gh auth status`):

```bash
gh repo create SEU-USUARIO/fulltv --public --source=. --remote=origin --push
```

Ou crie o repositório vazio em github.com/new e depois:

```bash
git remote add origin https://github.com/SEU-USUARIO/fulltv.git
git push -u origin main
```

## 3. Publicar o APK em uma Release

```bash
gh release create v0.11.10-fulltv \
  ../FullTV-0.11.10-debug.apk \
  --title "FullTV 0.11.10 (debug)" \
  --notes "Primeira build FullTV: tema laranja, pt-BR, EPG automático, gravação MP4 e guia com densidade/D-pad."
```

Ou use **Releases → Draft a new release** no site e anexe o APK.

## 4. Ativar atualizações automáticas (opcional)

Sem isso o app não checa atualizações (proteção contra instalar o APK oficial do OpenTV).
Depois de definir o repositório, edite `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "UPDATE_REPO_SLUG", "\"SEU-USUARIO/fulltv\"")
```

Gere um novo APK, publique outra Release com tag maior (ex.: `v0.11.11-fulltv`) e anexe o novo
APK. A partir daí o app baixa as releases deste repositório.

## 5. GitHub Actions

O projeto herdou os workflows do upstream em `.github/workflows/`. Antes de deixá-los rodar:

- confira `build.yml` e `release.yml`;
- configure os secrets de assinatura (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`) se quiser releases assinadas;
- revise `pages.yml` antes de ativar o GitHub Pages;
- se não for usar CI agora, desative os workflows em **Settings → Actions**.

## Checklist legal (GPL-3.0)

- O repositório deve ser **público** e conter o código-fonte correspondente ao APK publicado.
- Mantenha `LICENSE`, os créditos ao OpenTV e o `README-UPSTREAM.md`.
- Não remova os cabeçalhos de copyright dos arquivos derivados.
- O `opentv-modificacoes.patch` é opcional, mas ajuda a cumprir a transparência das mudanças.
