# Projet : WhisperBoard

## Quoi

Clavier Android avec **transcription vocale**, fork de
[HeliBoard](https://github.com/Helium314/HeliBoard). Bouton micro dans la
toolbar → dictée insérée dans le champ courant.

Deux moteurs STT :
- **Cloud — Deepgram Nova-3** : ~300 ms, quasi instantané (cle API saisie dans les settings in-app, jamais committee)
- **Local — whisper.cpp** : 100 % on-device, hors-ligne, aucune donnee envoyee
- **Auto** : cloud si dispo, bascule local hors-ligne

3 modeles Whisper (Base 142 Mo / Small 488 Mo / Small FR), telechargement in-app.
Langues : FR, EN, NL, DE, auto.

## Stack

- **Kotlin / Java** (clavier, UI, logique STT) + **C++** (whisper.cpp via NDK/CMake)
- **Gradle KTS** (`build.gradle.kts`), `./gradlew assembleRelease`
- APK distribues via GitHub Releases (repo **public** `david-digitis/WhisperBoard`)

## Place dans l'ecosysteme

Successeur de **DICTABOARD** (abandonne — ex-fork HeliBoard+Whisper++, retire
du monorepo DAVID-DEV). Cousin de **DIKTO** (meme besoin dictee, mais desktop
Electron + sherpa-onnx). Un dossier `.tmp-sherpa/` indique une exploration
sherpa-onnx (Parakeet) en cours — code experimental, hors build principal.

## Securite

- `whisperboard-release.jks` (keystore de signature release) : **JAMAIS
  committer**. Gitignore + absent de l'historique (verifie). Sans lui pas de
  build release signe — le garder hors repo (backup perso securise).
- `local.properties`, `keystore.properties` : gitignored, jamais committes.
- Cle Deepgram : saisie dans les settings de l'app, stockee cote appareil.

## Liens

- Repo : <https://github.com/david-digitis/WhisperBoard> (public)
- Upstream : <https://github.com/Helium314/HeliBoard> (voir `README-upstream.md`)
- STT local : <https://github.com/ggerganov/whisper.cpp>
