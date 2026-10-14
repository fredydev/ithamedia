# Ithamedia PDF — application Android

Boîte à outils PDF native (Kotlin) qui fonctionne entièrement sur le téléphone, hors ligne.

## Organisation

L'app a deux onglets :

- **Outils** : une grande carte « Scanner un document », la grille des outils et les fichiers récents.
- **Mes fichiers** : la bibliothèque de l'app, avec des dossiers. Tout ce que créent les outils y est
  rangé. Chaque fichier a un menu d'actions : ouvrir, signer, réorganiser, compresser, fusionner,
  partager, enregistrer sur l'appareil, renommer, déplacer, supprimer.

Chaque outil se termine par le même écran : **Ouvrir · Partager · Enregistrer sur l'appareil**.
Un PDF reçu par « Ouvrir avec… » ou « Partager » depuis une autre app est importé dans Mes fichiers.

## Outils

| Outil | Ce qu'il fait |
|---|---|
| Scanner un document | Scanner intelligent ML Kit : détection des bords, correction de perspective, filtres. Repli sur photo / galerie sans services Google Play. |
| Images en PDF | Assembler des photos de la galerie, réordonner, pivoter, format A4 ou taille de l'image. |
| Signer | Signature manuscrite (mémorisable), date, « Fait à …, le … », « Lu et approuvé », texte libre. |
| Réorganiser | Grille de miniatures : glisser pour déplacer, pivoter, supprimer (avec annulation). |
| Fusionner | Plusieurs PDF dans l'ordre choisi. |
| Compresser | Trois niveaux. Seules les images sont recompressées : texte et vecteurs restent intacts. Si aucun gain n'est possible, l'original est conservé. |
| Word en PDF | Conversion .docx sur l'appareil : styles, mise en forme, listes, tableaux, images, marges. Texte sélectionnable. Pas d'en-têtes/pieds de page, notes ni zones de texte. Les anciens .doc ne sont pas pris en charge. |

La signature est une image apposée sur la page (pas une signature électronique à certificat).

Bibliothèques : `PdfRenderer` (affichage), [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
(écriture), ML Kit Document Scanner (scan).

## Compiler

Prérequis : JDK 17 et le SDK Android (API 34), par exemple via Android Studio.

```bash
cd android-pdf-signer
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Le workflow GitHub Actions `.github/workflows/android-pdf-signer.yml` compile l'APK à chaque push
et le publie comme artefact (`pdf-signer-debug-apk`).

Android 8.0 (API 26) minimum.

## Crédits

- Icônes : [Lucide](https://lucide.dev) (licence ISC), converties en VectorDrawable.
- Polices : [Instrument Serif](https://github.com/Instrument/instrument-serif) et
  [IBM Plex Mono](https://github.com/IBM/plex), licence SIL Open Font License 1.1.
