# Changelog

## 0.11.29 (atualização estável) — 2026-11-05

- **Navegação por controle remoto:** ao voltar do primeiro canal para a rail, aguardar a animação real,
  rolar até a categoria selecionada e pedir foco somente depois que o item estiver visível. Um foco novo
  no guia cancela a restauração pendente. A rail agora distingue foco de seleção e expõe o estado
  selecionado à acessibilidade; o foco no botão de favorito também atualiza o canal destacado/preview.
- **Validação local:** 2 testes de política de navegação, 9 testes do gate de assinatura e 4 testes do
  verificador de wheels passaram. Na suíte Android completa, 181/187 passaram; seis
  `XmltvChannelParseTest` falharam antes das assertions pela ausência de
  `conscrypt_openjdk_jni-linux-aarch_64` no host ARM64. `lintVitalRelease` e `assembleRelease` passaram;
  o R8 emitiu avisos de parsing de metadados Kotlin. Não houve teste em Fire TV/Android TV físico.
- **Compatibilidade:** atualização do pacote normal `app.fulltv`, versionCode 41, assinada com o mesmo
  certificado legado das instalações existentes, conforme autorização específica do proprietário. O
  certificado é Android Debug compartilhado, não uma chave de produção.
- **APK universal:** minSdk 24, targetSdk 35, quatro ABIs; tamanho 165.277.179 bytes; SHA-256
  `39ba2855cbcd6e91a24f3c612d581d8fb7ddf8b96e7d5d804efcbdbb08b9c562`; certificado SHA-256
  `01ad68a30d48753a4909ce350ddfd98ec5a30af684c2efd54955184caac69b91`.

## 0.11.28 (atualização estável) — 2026-11-04

- **Atualização pelo app:** permitir que o download do APK siga o redirecionamento HTTPS público do GitHub Releases para `release-assets.githubusercontent.com`. O instalador continua validando tamanho, SHA-256, pacote, versão e certificado antes de abrir o instalador Android.
- **Compatibilidade:** pacote normal `app.fulltv`, versionCode 40, assinado com o certificado legado Android Debug já usado nas instalações públicas, conforme autorização específica do proprietário. Esse certificado não é uma chave de produção e esta exceção não vale para versões futuras.
- **Validação:** 9 testes do gate de assinatura e 4 testes do verificador de wheels passaram; `lintVitalRelease` e `assembleRelease` também. Na suíte Kotlin completa, 179/185 passaram; seis testes XMLTV falharam antes das assertions pela biblioteca Conscrypt nativa ausente no host Linux ARM64. Não houve teste em aparelho físico.
- **APK universal:** `app.fulltv`, versionCode 40, minSdk 24, targetSdk 35 e quatro ABIs. Tamanho 165.277.175 bytes; SHA-256 `ac576d03327b10bdb87d552a7aaf8286c763268e37038835dc668b3b48593200`; certificado SHA-256 `01ad68a30d48753a4909ce350ddfd98ec5a30af684c2efd54955184caac69b91`.

## 0.11.27 (atualização estável) — 2026-11-03

- **FTP na rede local:** combinar mDNS com uma verificação limitada da porta 21 somente no bloco IPv4 privado local (máximo 254 endereços, até 32 conexões simultâneas e sem login). O formulário aceita IP, IP:porta ou `ftp://IP:porta/pasta` colado no endereço.
- **Salvar e ativar separadamente:** a configuração cifrada pode ser salva antes de o servidor responder; FTP só vira destino de gravação após aceite explícito do aviso de transferência sem criptografia. O formulário apresenta erros localizados e não exige conexão para guardar o endereço.
- **Assinatura compatível:** pacote normal `app.fulltv`, versionCode 39, assinado com o mesmo certificado legado usado na v0.11.26, conforme autorização específica do proprietário. O certificado é Android Debug, não é uma chave de produção e não deve ser reutilizado em versões posteriores.
- **Validação local:** os 17 testes FTP focados, 9 testes do gate de assinatura e 4 testes de hash Chaquopy passaram. Na suíte completa, 179/185 passaram; seis testes XMLTV falharam antes das assertions por falta de `conscrypt_openjdk_jni-linux-aarch_64` neste host ARM64. `lintVitalRelease` e `assembleRelease` passaram; não houve teste físico em Fire TV nem em roteador.
- **APK universal:** `app.fulltv`, versionCode 39, minSdk 24, quatro ABIs; certificado SHA-256 `01ad68a30d48753a4909ce350ddfd98ec5a30af684c2efd54955184caac69b91`. Tamanho 165.277.179 bytes; SHA-256 `95c7bf5230e7c34d49d96dfaf076a1d1755158609b1c6b23cbce11ac4b7ea5f8`.

## 0.11.26 (atualização estável) — 2026-11-03

- **FTP em dispositivos da rede local:** preencher com um toque o IP do gateway Wi-Fi/Ethernet e
  localizar serviços FTP anunciados por mDNS. A descoberta aceita somente IPv4 privado e não faz
  varredura de portas; o roteador/computador ainda precisa ter FTP habilitado e permissão de escrita.
- **Navegação por controle remoto:** redimensionar a Activity com o teclado, rolar o campo focado para
  a tela e oferecer ações Próximo/Concluído para percorrer o formulário FTP sem ficar preso no teclado.
- **Compatibilidade de atualização:** esta é uma release estável do pacote normal `app.fulltv`, código
  38, assinada com o mesmo certificado legado da v0.11.25 conforme autorização específica do proprietário.
  O certificado é Android Debug, não é uma chave de produção e não deve ser usado após esta versão.
- **Validação:** quatro testes de descoberta FTP passaram. Na suíte Kotlin, 173/179 passaram; seis
  `XmltvChannelParseTest` falharam antes das assertions por falta de `conscrypt_openjdk_jni-linux-aarch_64`
  no host ARM64. `lintVitalRelease` e `assembleRelease` passaram; não houve teste físico em Fire TV/roteador.
- **APK universal:** `app.fulltv`, versionCode 38, minSdk 24, quatro ABIs; certificado SHA-256
  `01ad68a30d48753a4909ce350ddfd98ec5a30af684c2efd54955184caac69b91`. SHA-256 do APK:
  `ae54b38f42b6f122ba86e0f80730fd2151f8b8f88331d5387c7997dadfc80df8`.

## 0.11.25 (atualização estável) — 2026-11-03

- **Controle parental e PIN:** exigir autorização nos pontos de entrada de canais ocultos, incluindo
  player direto, retomada, lembretes e Web Manager; usar backoff monotônico e manter os controles
  bloqueados quando a persistência do PIN falhar.
- **Serviços LAN/TLS:** limitar conexões/handshakes, adicionar deadlines e fechar servidores quando
  suas telas deixam de estar ativas. O pairing por QR aceita navegadores que omitem `Origin` ou
  reportam origem opaca `null`, mas mantém `Host`, token e código obrigatórios; origens concretas
  diferentes continuam bloqueadas.
  As mudanças continuam sem fallback para HTTP.
- **SSRF e respostas:** escopar a confiança de origens por operação, revalidar redirects/destinos,
  pinçar DNS para mídia pública e limitar o corpo de respostas TMDB. O Streamlink de YouTube usa
  guard HTTPS com DNS público validado; FTP não foi adicionado às fontes de mídia.
- **Segredos, sync e gravações:** preservar segredos se a migração do Keystore falhar, autenticar e
  rejeitar replay de bundles NAS, exigir criptografia SMB negociada e melhorar finalização/recuperação
  de gravações e remux interrompidos. O fallback destrutivo do Room foi removido.
- **Destino FTP opcional:** aceitar somente IPv4 privado numérico e pasta relativa; mostrar risco do
  protocolo sem criptografia e exigir consentimento. Credenciais ficam cifradas no Keystore; uploads
  binários são staged em `.partial`, confirmados e renomeados, com timeout, leitura/seek de gravações
  concluídas e limpeza/verificação de falhas. Apache Commons Net 3.13.0 está fixado e verificado.
- **Cadeia de fornecimento e assinatura:** revalidar hashes das wheels Chaquopy do wheelhouse imediatamente
  antes da instalação. Por compatibilidade, esta versão tem exceção de assinatura autorizada e restrita ao
  certificado legado Android Debug; não é uma chave de produção e não deve ser reutilizada em releases futuras.
- **Atualização pelo app:** esta versão é publicada como estável (não pré-release), pois o atualizador ignora
  versões de teste e só oferece releases estáveis mais novas.
- **Riscos restantes:** FTP continua transmitindo usuário, senha e vídeo em claro por escolha explícita;
  Room ainda não usa SQLCipher e `lxml` 5.3.0 permanece por falta de wheel Android corrigida no índice
  Chaquopy consultado. Ver relatório desta rodada para limitações e resultados de teste.
- **Compatibilidade:** o APK universal usa `app.fulltv`, código 37 e o certificado SHA-256 legado
  `01ad68a30d48753a4909ce350ddfd98ec5a30af684c2efd54955184caac69b91`, para atualizar a instalação normal.
  O APK debug tem outro package (`app.fulltv.debug`) e não compartilha a lista M3U do app normal.
- **Validação:** `PairingHttpTest` passou. Na suíte Kotlin completa, 169/175 passaram; seis testes XMLTV
  falharam antes das assertions por ausência de Conscrypt nativo no host ARM64. Não houve teste físico em
  Fire TV/TV box.
- **Build:** `:app:assembleDebug`, `:app:assembleRelease` e `:app:lintVitalRelease` passaram. O APK universal
  foi verificado como `app.fulltv`, versionCode 37, minSdk 24, quatro ABIs e certificado esperado. Seu SHA-256
  é `b64dc7d9fb4f36b8d8525f113ca01afb3db3e60e41d93f9ae79cd21508f47a8a`.

## 0.11.24 (pré-lançamento de teste)

- **Português do Brasil:** preencher as 19 traduções ausentes, substituir vocabulário e construções
  de pt-PT por texto brasileiro consistente e localizar todos os estados do diálogo de atualização,
  incluindo falha de download e permissão para instalar apps.
- **Gravação em Fire TV:** facilitar o foco e a seleção da pasta USB pelo controle remoto, reabrir a
  última pasta SAF, explicar a navegação por D-pad e confirmar o destino apenas depois de validar o
  acesso persistente de leitura/escrita. A tela de segundo plano agora informa quando o Fire OS não
  oferece uma configuração compatível.
- **Validação:** `assembleDebug`, `assembleRelease` e `lintVitalRelease` concluíram. A suíte registrou
  132/138 testes aprovados; seis casos XMLTV não carregaram Conscrypt no host Linux ARM64. Não houve
  teste em Fire TV/aparelho Android físico nesta sessão.

> Pré-lançamento para teste manual. Usa a mesma assinatura de transição da v0.11.23 para permitir
> atualização por cima; essa chave debug não é uma chave de produção.

## 0.11.23 (pré-lançamento de teste)

- **Subtitles:** remove PCM audio capture and Whisper speech transcription. Portuguese translation
  now processes only readable text cues emitted by the selected subtitle track supplied by the
  channel. If the channel has no readable text captions, FullTV does not invent any; original
  subtitles remain visible if translation is unavailable. The unused Whisper native dependency is
  removed, and any old private Whisper model/WAV files are cleaned up in the background.
- **YouTube playlists:** expand a YouTube `/results` URL embedded as an M3U channel into up to five
  separate live channels, each retaining its own watch/video ID so the existing YouTube Streamlink
  playback and recording paths can target it individually. Preserve the search page's filter/order.
- **LAN transport:** phone pairing, Web Manager and device sync use TLS without an HTTP fallback;
  the TV shows a certificate fingerprint, and device sync requires a matching public-key pin.
- **Secrets and sync:** move SMB/TMDB/add-on secrets into AES-GCM storage backed by Android
  Keystore, strengthen parental PIN verification with keyed HMAC and lockout, and keep raw provider
  URLs and group keys out of NAS sync bundles using keyed references and migration from v3.
- **Network and parsers:** require validated HTTPS, block untrusted private destinations and unsafe
  redirects, bound network/parser input, reject XML DTD/external entities, and harden LAN request
  framing and CSRF protections.
- **Runtime guards:** skip alarm notifications when the user denies `POST_NOTIFICATIONS`, and gate
  picture-in-picture calls on Android 8.0/API 26+.
- **Storage and updates:** bind SMB paths to the configured encrypted SMB3 destination, prevent
  recording overwrite, validate APK digest/package/version/signature before installation, and require
  explicit release signing. Pin Gradle/Python dependencies, update the lint-only Apache HttpClient to
  4.5.14, and verify wrapper and wheel checksums.
- **Validation limits:** debug/release APK assembly and `lintVitalRelease` passed. Of 138 complete-suite
  tests, 132 passed; six XMLTV/Robolectric cases cannot load the Conscrypt native library on the Linux
  ARM64 sandbox before assertions. lxml 5.3.0 remains in the Android wheel because Chaquopy does not
  currently publish the fixed 6.1.0 wheel for this Python/ABI set; affected parser entry points are
  hardened locally. Room encryption, production signing-key migration and real-device TLS/usability
  tests remain outstanding. Full lint still reports untranslated strings and Media3 opt-in usages;
  this release used the explicit debug-signing opt-in and is a test build, not a production build.

> Pré-lançamento público para teste manual. A assinatura debug compartilhada permite atualizar a
> instalação v0.11.22, mas não é uma chave de produção. Consulte os limites de validação e os riscos
> ainda pendentes antes de usar esta build com credenciais reais.

## 0.11.22

- **HTTPS-only sources:** bare hosts now default to HTTPS; explicit HTTP/other schemes are blocked
  before save with a clear message. M3U format checks state that they do not test reachability.
- **EPG reliability:** download and parse a new guide into private staging rows, then replace the
  previous programmes and aliases in one transaction. Failed, cancelled, invalid or empty refreshes
  keep the previous guide available.
- **Player zapping:** consume only the guide queue matching the launch channel; direct opens from
  search, resume or notifications build a stable fallback list and discard stale queue state.
- **Playlist completeness:** preserve SD/HD variants sharing one `tvg-id`, deduplicate only identical
  stream URLs, and retain original playlist order with stable source/order tie-breakers.
- **Scheduled recordings:** show exact-alarm permission status and link to Android settings when
  access is unavailable. The backup screen now separately emphasizes that video files are not moved.
- **Recording stability:** keep Streamlink limited to YouTube, resolve Stalker URLs when capture
  starts, and rewrite `.m3u8` to `.ts` only for Xtream. The destination now opens only after the
  first media chunk arrives; that chunk is flushed (and synced for local files), so a crash before
  media creates no empty video and a later crash preserves the partial capture. Progress appears
  from the first chunk, and old zero-byte internal files from interrupted captures are removed on
  the next launch. Destination write errors no longer masquerade as stream reconnects.
- **Automatic Portuguese captions:** use the multilingual Whisper tiny Q5 model (~31 MiB) instead
  of the unquantized ~78 MB model; load it only when enabled for playback, release it when leaving
  the player, and skip silent 5-second audio windows with an energy-based VAD. Remove the old model
  file when the updated caption engine first starts.
- **Validation:** targeted JVM tests for HTTPS, M3U order/variants, player queue handoff, PCM/VAD
  and recording URL policy pass. The full suite has 105 tests: 101 pass; four XMLTV/Robolectric tests
  cannot load Conscrypt's ARM64 native library in the build sandbox. No interactive Fire TV/Android
  device test was available.
- **Signing:** the release uses the same debug-key certificate as v0.11.21 for upgrade compatibility;
  this is not a production signing key, so a future key change will require an install migration.

## 0.11.21

- **Web manager:** show the manual HTTP address and required access token in readable groups, and
  explain that token lines must be joined without spaces. Clarify that the same-LAN URL uses HTTP,
  not HTTPS, and improve the Brazilian Portuguese wording.

## 0.11.20

- **Startup stability:** defer full VOD shelf scans until Movies/Shows is opened, skip heavy VOD
  downloads in periodic background refreshes, and log isolated startup task failures instead of
  letting them terminate the foreground app. SQLite `VACUUM` is no longer run automatically.
- **Storage:** add a usage breakdown and an explicit, free-space-checked guide-database compaction
  action. It never removes recordings. The Whisper model can be removed and downloaded again later.
- **M3U guides:** detect `url-tvg` / `x-tvg-url` attributes anywhere in a playlist. Every unique
  guide is added and enabled automatically; multi-region playlists may use substantial network and
  storage. Settings can pause and clear extra feed rows. Cleartext HTTP feeds remain blocked.
- **Clock:** add an optional local-time badge in the bottom-right corner of every screen.

## 0.11.19

- **Portuguese default:** use Brazilian Portuguese (`pt-BR`) when no language preference exists;
  Portuguese (Portugal) remains an explicit option. Existing generic `pt` preferences migrate to
  `pt-PT`, while a previously explicit system-language choice remains unchanged.

## 0.11.18

- **Terminology:** Use “Live TV” in Portuguese navigation, recordings and settings instead of “TV em direto”.

## 0.11.17

- **YouTube Live Search:** add a public `/results` URL as a source; the bundled yt-dlp extractor
  refreshes up to five currently live results, which open through the bundled Streamlink resolver.
- **YouTube live recording:** capture media bytes through Streamlink's HLS reader into the selected
  recording destination, with reconnects while the scheduled capture remains active.
- **Five new TV compositions:** Oi-style hybrid guide/preview, top Info player, full-screen EPG,
  auto-hiding linear-TV banner, and bottom Info bar with a wider channel drawer.
- **Validation:** the search extractor matched the live video supplied by the user, the generic
  Streamlink reader returned HLS bytes, and 26 targeted JVM tests passed. A real-device playback
  test remains necessary; this sandbox is rate-limited by YouTube anti-bot responses.

## 0.11.16

- **Stable channel order:** keep each provider's channels together in source order, use a stable
  row order when indices tie, and preserve the first-seen order of M3U categories. This avoids
  alphabetical tie-breaking that made combined playlists look shuffled.

## 0.11.15

- **Install FullTV beside original OpenTV:** FullTV now has Android package ID `app.fulltv`; the
  original OpenTV can keep `app.opentv`, so the two apps no longer conflict on fresh installations.
- **Migration note:** Android treats this as a separate app. Settings and database from older
  FullTV builds using `app.opentv` are not copied automatically. Remove an old FullTV installation
  before installing original OpenTV; removing it can erase its local data.
- **YouTube live channels:** Streamlink is bundled to resolve `youtube.com/@handle` live channels.
- **Automatic Portuguese subtitles (opt-in):** Whisper transcribes locally and ML Kit translates
  on-device; models download on first use and playback audio is not uploaded.
- **Recording horizon and TV UI:** configure series lookahead from 2 to 14 days, choose from six
  interface styles, and use the four-channel mosaic with one audio tile at a time.
- **Encrypted device backup:** export/import sources, credentials, settings and syncable state in a
  passphrase-protected AES-256-GCM `.fulltv` file; video files and USB folder grants are excluded.
- **Minimum Android version:** Android 7.0/API 24 or newer.

## 0.11.14

- **YouTube live channels:** bundle Streamlink and resolve `youtube.com/@handle` links to the
  current live stream before playback; the existing player handles the resulting media.
- **Automatic Portuguese subtitles (opt-in):** locally transcribe live PCM with Whisper and
  translate the recognized text on-device. Model downloads are required; playback audio is not uploaded.
- **Series recording horizon:** configure upcoming EPG scan/retention from 2 to 14 days and refresh
  the guide/rescan series rules from Recording settings.
- **TV interface options:** six visual presets, channel-zapping style, category portal, and a
  four-channel mosaic with per-tile audio control.
- **Device backup:** export/import an AES-256-GCM encrypted `.fulltv` file protected by a passphrase,
  carrying sources, credentials, portable settings and syncable user state. Video files and USB
  folder grants are not copied.
- **Minimum Android version:** raised to Android 7.0/API 24 for the embedded Python and native
  speech runtime; older Android 6/API 23 devices can no longer install this build.

## 0.11.10

- **Fire TV: app row fix, take two.** v0.11.9 replaced the placeholder artwork but the stick still
  wouldn't list OpenTV. Fire OS reads a sideloaded app's launcher icon straight out of the APK by
  path, and it only looks in `res/drawable…` folders (a known Fire OS 7/8 quirk that leaves apps
  with icons under `res/mipmap…` broken or missing). The icon now lives in `drawable-*dpi` and is
  referenced as `@drawable/ic_launcher`, and the release build keeps real resource paths instead
  of aapt2-shortened ones, so the stick can find it. After updating, uninstall OpenTV, restart the
  stick and install it again from Downloader so the launcher re-reads the APK.

## 0.11.9

- **OpenTV now shows up on the TV home screen.** The app was built with a placeholder vector
  banner and an adaptive-only (XML) launcher icon, which the Fire TV and Google TV launchers
  either rendered as a blank dark tile with no name on it or failed to render at all, so a
  sideloaded install only appeared under Settings → Applications. The APK now ships a proper
  320×180 **banner with the OpenTV wordmark** (Google TV / Android TV home screen) and plain
  **PNG launcher icons at every density** (Fire TV app row, phones), which is what both
  launchers expect. Install the update and the OpenTV tile appears in the apps row — on the
  new Fire TV layout it lands at the end of the row, so hold Select → Move to bring it forward.

## 0.11.8

- **Edit a saved provider.** Settings → Providers now has an **Edit** button on each provider, so you
  can fix a server address, password, MAC or name in place instead of removing and re-adding it —
  your favourites, hidden channels and per-provider settings are kept.
- **Stalker portals: fewer dropped sessions.** A portal that occasionally rejected a call
  mid-session (channels loading only "sometimes") now gets one automatic re-handshake and retry, so
  an intermittent refusal becomes a silent re-auth rather than an empty channel list or a channel
  that won't tune.
- **Player controls stay clear of the system bar off-TV.** On phones and tablets the bottom control
  bar (subtitles, audio, quality, aspect ratio) could be drawn under the navigation bar and cut off;
  it's now inset above it. No change on Android TV.

## 0.11.7

- **Stalker portals: closer to a real box.** Building on v0.11.6, OpenTV now sends the rest of the
  identity a real MAG set-top box presents at login — the auth token in the session cookie (not just
  the header), a hardware-version hash, a timestamp and the box's API signature — and it probes a
  couple more portal paths. Some Ministra/Stalker panels only return the channel list when they see
  all of this, so lines that connected but showed no channels have a better chance of loading. If a
  portal still comes up empty, please open an issue and say which panel software it runs.

## 0.11.6

- **Stalker portals: full set-top-box identity.** OpenTV now authenticates to Stalker/Ministra
  portals the way a real MAG box does — sending the device serial, `device_id`, a signature and a
  metrics blob derived from your MAC, instead of the MAC on its own. Many portals won't hand over
  the channel list or stream links to a box that only presents a MAC, which is why some lines that
  worked in other apps came up empty in OpenTV. This lets those portals authorise OpenTV. If a
  portal still fails, please open an issue and say which one — it helps a lot.

## 0.11.5

- **Add a provider from your phone or laptop.** The "Manage on phone or laptop" web page now has an
  **Add a provider** section — pick Xtream, M3U or Stalker portal, fill it in with a real keyboard,
  then Test and Add. Channels load straight onto the TV, so you never have to type a server address
  or a MAC on the remote again.
- **Fixed the "camera won't scan it?" web address.** The manage screen was printing that link without
  its access token, so typing it into a browser hit a dead "Not found". It now shows the full working
  URL, and the token is short enough to type.


## 0.11.4

The big DVR release. Recordings behave like Sky Q, you can record straight to a NAS, watch a
recording while it's still taping, and the whole interface now speaks 30 languages.

### Recording & DVR

- **Watch a recording while it's still recording.** Start playing something the moment it begins
  taping — OpenTV reads the growing file straight off disk, so it costs **zero extra connections**
  to your provider. On a single-stream account that's the difference between "wait until it's
  finished" and "watch now". Fast-forward and rewind work within whatever's been taped so far.
- **Record straight to a NAS.** Point recordings at a network share over SMB (Settings →
  Recordings → NAS, or set it up from the phone/laptop web manager) so a cheap box isn't boxed in
  by its own storage — and every OpenTV in the house can reach the same recordings.
- **Single-connection auto-switch.** On a one-stream provider, if a recording is due to start on
  another channel, OpenTV moves the screen onto that recording as it begins — with a **30-second
  warning** first and a *Keep watching* button if you'd rather not. No more "why did my live stream
  just cut out?" (Recordings → Recording behaviour → *Auto-switch when recording starts*.)
- **Clash handling with multi-provider fallback.** Book two overlapping recordings and, if more
  than one of your providers carries that channel, OpenTV records each from a **different provider**
  so neither is cut. On a single provider it flags the clash instead.
- **Recording padding.** Start each recording a minute early and run a few minutes past the listed
  end, so a late kick-off or an overrun isn't clipped. Adjustable per side.
- **Series links show the next episode.** A series link now tells you exactly when it next records.
- **Storage readout.** See how much space recordings use and how much is left.

### Live TV

- **Pause & rewind live TV** *(experimental, opt-in).* Holds the last couple of minutes so you can
  pause and jump back. Off by default — it uses more memory.

### Languages

- **30 languages.** The whole interface is translated into Spanish, French, German, Italian,
  Portuguese, Dutch, Polish, Russian, Turkish, Arabic, Simplified Chinese, Japanese, Korean, Hindi,
  Swedish, Danish, Finnish, Norwegian, Czech, Greek, Romanian, Hungarian, Ukrainian, Indonesian,
  Thai, Vietnamese, Bulgarian, Slovak, Croatian and Persian. Settings → Language (each listed in
  its own name), or leave it on *System* to follow your device.

### Fixes & polish

- Smoother watch-while-recording, especially from a NAS: playback now keeps a buffer behind the
  live edge so a network share doesn't stutter at the write head.
- Back never lands you on a stray live channel or drops you out by accident — coming back from a
  recording returns to your recordings, and Back on the home screen asks before it exits.
- Password fields are masked, with a show/hide toggle.
- Recording stability: NAS write timeouts and reconnect handling, so a quiet network doesn't
  silently freeze a capture.

### Notes

- Everything stays on your device — provider logins never leave the box.
- Free and open source (GPL-3.0): https://github.com/opentvproject/opentv


## 0.10.0

- **Movies & Shows, redesigned.** A Plex-style layout with big artwork, cast & director, "more with
  this cast" rows, and cleaned-up titles (no more "NF -" / "(KR)" junk). Optional: add your own free
  TMDB key in Settings → Metadata and OpenTV fills in any posters, backdrops, cast or synopses your
  provider left blank — the key stays on your device.
- **No more stuck "Loading movies & shows".** The movies/series catalogue is cached, so it loads
  instantly on later launches instead of re-downloading every time — and the live preview no longer
  stutters while it loads.
- **Record to a USB / external drive.** Settings → Recordings → USB, pick a folder on a plugged-in
  drive, and recordings write straight there (no storage permission needed) and play back in-app.
- **Recordings screen overhaul.** Scheduled recordings show *when* they'll record and sit in their
  own "Scheduled" section; failed ones have a **Retry**; each is tagged to a profile; and bookings
  re-arm on every launch so a force-stop or app update can't quietly drop them.

## 0.9.0

- **Manage your channels from a phone or laptop.** A new "Manage on phone or laptop" screen in
  Settings shows a QR code and a link — open it on any device on your wifi and you get a proper web
  page to browse, **rename**, hide, favourite and **reorder** channels with a real keyboard and
  mouse. Changes apply to the TV instantly. Local-only: the TV is the server, nothing touches a
  cloud.
- **Recording keeps going when you leave the app or the box sleeps.** OpenTV can now ask Android to
  exempt it from battery optimisation (Settings → Recording → "Recording in the background"), which
  is what keeps recordings running in standby and when you switch away — swiping OpenTV out of
  recents no longer stops a recording either.
- **Rename channels.** Give a channel your own name; it sticks and survives a guide refresh (from
  the web manager for now).
- **Tidier update notes.** The "update available" prompt shows a clean summary of what's new instead
  of build metadata.

## 0.8.0

- **No more endless "Loading channels".** If a provider fails to load or comes back empty (a wrong
  login, a dead server), the guide now shows a clear error with **Retry** and **Provider setup**
  buttons instead of spinning forever.
- **Set up on a phone without the QR dance.** Installing on a phone now shows the login form
  directly so you can type your details in; the QR code is only offered on a TV, where typing is the
  painful part.
- **Paste into the setup fields.** The URL, username and password fields — and the on-screen
  keyboard — now have a **Paste** button, so a copied Xtream line drops straight in.
- **MPEG-TS or HLS, your choice.** Each Xtream provider now has a **Stream format** toggle. If your
  panel only serves `.ts` and channels wouldn't play, switch to MPEG-TS and they will. Defaults to
  HLS, so nothing changes unless you need it.
- **Parental controls scrolls.** The hidden-categories list is reachable to the bottom now, however
  many categories your provider has.
- **A real Channel Manager.** Settings → Channels is now a browsable, two-pane manager: pick a
  category on the left, see its channels on the right, hide or favourite each one — including
  hidden channels so you can bring them back. With more than one provider, a **source filter** keeps
  them separate instead of merged into one list.

## 0.3.0

- **Profiles.** Add local profiles (just a name — no accounts) from the person icon in the top
  menu, and switch between them. Each profile keeps its own resume points and watched state, so
  your half-watched film doesn't show as watched on someone else's profile. Existing resume points
  become the default "Me" profile's — nothing is lost.
- **Continue Watching.** Movies and Shows now open with a "Continue watching" shelf for the active
  profile — pick up your last film or episode where you left off, with a progress bar on each.
- **Local sync (no servers).** Settings → Sync copies your continue-watching between two OpenTV
  devices over your own wifi: one device shares behind a six-digit code, the other receives. It's
  local-only — nothing leaves the house — the household answer to the cloud sync that died with
  Viewella.

- **Proper player controls.** A single control bar slides up from the bottom with play/pause,
  rewind/forward (where the stream allows it), and pickers for **subtitles, audio track, quality
  and aspect ratio**. It hides after a few seconds and any remote button brings it back —
  nothing is ever left painted permanently over the picture.
- **Captions that actually show.** Subtitles are now chosen from the real tracks in the stream
  (and can be turned off), instead of a blind on/off that often left the renderer enabled but
  nothing on screen. Audio-track switching works the same way for multi-language streams.
- **Page through the guide.** Earlier / Now / Later buttons above the guide move the timeline
  forward and back, so you can see what's on later this evening with a remote.
- **Live preview in the guide.** The highlighted channel now plays, muted, inside the preview
  pane. It uses a single player that stops the instant you go full-screen or leave the guide —
  no second decoder — and it can be turned off in Display & playback for older boxes.
- **Channel manager.** Settings → Channels: search for a channel and hide it from the guide (or
  favourite it). Hidden channels drop out of Live TV and search but stay here, greyed, so you can
  bring them back. Hiding covers every quality variant of a channel at once.
- **Parental controls.** Set a 4-digit PIN and mark categories as hidden — they drop out of the
  guide, All channels and search until you unlock them (and with a PIN set, the parental screen
  itself is locked). In Settings → Parental controls.
- **Unified search with an on-screen keyboard.** Search now has its own screen with a d-pad
  keyboard (no more "type on your phone") and covers **channels, movies and shows** together, with
  live results as you type. Reachable from the top menu.
- **Loading indicators for Movies and Shows.** Both now show a spinner while the catalogue is
  syncing instead of a premature "nothing here".
- **Settings and Search in the top menu.** Both now live top-right, next to Live TV / Movies /
  Shows, instead of being buried behind the guide's icons.
- **A single Settings screen.** One Settings hub — Providers (add / remove / re-test sources), TV
  guide, Display & playback, Parental controls, and About (version, a manual update check,
  licence and links).
- **Dark / light / follow-system.** A new appearance setting. TV still defaults to dark; pick
  Dark or Light to force it on any device.
- **Quality picker only when there's a choice.** No more "Quality: Standard" on single-quality
  channels; the picker appears only when a channel actually has multiple qualities.
- New **Display & playback** settings screen (the sliders icon in the guide).

## 0.2.0

- **Guide detail pane** — highlighting a channel now shows its logo, what's on now with
  start/end times and a live progress bar, a synopsis when the guide carries one, and what's
  on next. Press to watch full-screen.
- **Live progress fill** on the current programme in the guide grid, and a focus highlight on
  the highlighted channel.
- **Fix: never asks for your provider again.** A cold launch could briefly decide "first run"
  before your saved sources had loaded and drop you on the setup screen — it now waits for the
  sources to load before deciding what to show.
- **Fix: no second video decoder on the guide**, which was locking up low-end boxes (a
  Chromecast could freeze). The detail pane is logo + guide info; the one decoder lives
  full-screen.

## 0.1.0

First public release.

- Xtream Codes and M3U/M3U8 sources
- Streaming XMLTV guide parsing with incremental, non-destructive sync
- Live TV playback on Media3/ExoPlayer with IPTV-tuned retry behaviour
- Movies and series catalogue
- Favourites, categories, search
- In-app self-update for sideloaded installs
- Single APK for Android TV, Fire TV, phones and tablets
