# Signer PDF — application Android

Application Android native (Kotlin) pour signer des documents PDF à la main, directement sur le téléphone.

## Fonctionnalités

- Ouvrir un PDF depuis le stockage, Google Drive, etc. (ou via « Ouvrir avec… » / « Partager » depuis une autre app)
- Navigation page par page
- Dessin de la signature au doigt ou au stylet (encre noire ou bleue)
- Mémorisation de la signature pour la réutiliser
- Ajout de texte : date du jour, « Fait à <ville>, le <date> » (ville mémorisée), « Lu et approuvé » ou texte libre
- Placement libre : glisser pour déplacer, tirer le coin pour redimensionner, appui long pour supprimer
- Plusieurs signatures et textes, sur une ou plusieurs pages
- Enregistrement d'une copie `<nom>_signé.pdf` : la signature est ajoutée au PDF d'origine sans le rasteriser
  (texte sélectionnable et qualité vectorielle conservés), pages pivotées comprises

Rendu des pages : `android.graphics.pdf.PdfRenderer`. Écriture : [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android).

> Il s'agit d'une signature manuscrite apposée (image), pas d'une signature électronique
> cryptographique (certificat PAdES).

## Compiler

Prérequis : JDK 17 et le SDK Android (API 34), par exemple via Android Studio.

```bash
cd android-pdf-signer
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Ou ouvrir le dossier `android-pdf-signer` dans Android Studio et lancer l'app.

Le workflow GitHub Actions `.github/workflows/android-pdf-signer.yml` compile l'APK à chaque push
et le publie comme artefact (`pdf-signer-debug-apk`).

Android 8.0 (API 26) minimum.
