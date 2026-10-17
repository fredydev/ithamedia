#!/usr/bin/env bash
# Test sur émulateur : lance l'app, parcourt l'accueil, Mes fichiers et chaque outil sur un
# PDF d'exemple, filme l'écran, prend des captures et échoue au premier plantage.
set -u
PKG=com.ithamedia.pdfsigner
OUT=smoke
mkdir -p $OUT/screens
crashed=0

check_crash() {
  if adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION"; then
    echo "!!!!! PLANTAGE après : $1"
    adb logcat -d -s AndroidRuntime:E | tee -a $OUT/crash.txt
    adb logcat -c
    crashed=1
  fi
}
shot() {
  adb exec-out screencap -p > "$OUT/screens/$1.png"
}

adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 ../.github/scripts/sample_pdf.py $OUT/Exemple.pdf
adb push $OUT/Exemple.pdf /data/local/tmp/Exemple.pdf

adb logcat -c
# Vidéo de tout le parcours (limite Android : 180 s par enregistrement)
adb shell screenrecord --bit-rate 6000000 --time-limit 175 /sdcard/parcours.mp4 &
REC=$!
sleep 1

adb shell am start -W -n $PKG/.HomeActivity
sleep 6
check_crash "lancement"
shot 01-accueil
# Copie le PDF d'exemple dans Mes fichiers (stockage privé de l'app, possible en debug)
adb shell run-as $PKG mkdir -p files/Documents
adb shell "cat /data/local/tmp/Exemple.pdf | run-as $PKG sh -c 'cat > files/Documents/Exemple.pdf'"
DOC=/data/user/0/$PKG/files/Documents/Exemple.pdf

SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400
sleep 2
shot 02-accueil-outils
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400
sleep 2
shot 03-accueil-catalogue

adb shell am start -n $PKG/.HomeActivity --es folder /data/user/0/$PKG/files/Documents
sleep 3
check_crash "Mes fichiers"
shot 04-mes-fichiers

i=10
for spec in \
  ViewerActivity: \
  OrganizeActivity:ORGANIZE SplitActivity:SPLIT CropActivity:CROP \
  SignActivity:SIGN SignActivity:STAMP AnnotateActivity:ANNOTATE \
  StampTextActivity:WATERMARK StampTextActivity:PAGE_NUMBERS StampTextActivity:HEADER_FOOTER StampTextActivity:BATES \
  ProtectActivity:PROTECT RedactActivity:REDACT SanitizeActivity:SANITIZE \
  CompressActivity:COMPRESS PropertiesActivity:PROPERTIES OcrActivity:OCR \
  ExportActivity:TO_IMAGES ExportActivity:TO_WORD FormActivity:FORM \
  MergeActivity:MERGE CompareActivity:COMPARE \
  TextToPdfActivity:TEXT BlankPdfActivity:BLANK; do
  act=${spec%%:*}; tool=${spec#*:}
  extra=""
  [ -n "$tool" ] && extra="--es tool $tool"
  adb shell am start -n $PKG/.$act $extra --esal paths $DOC
  sleep 4
  check_crash "$act $tool"
  shot "$i-${tool:-$act}"
  i=$((i+1))
  adb shell input keyevent KEYCODE_BACK
  sleep 1
  adb shell input keyevent KEYCODE_BACK
  sleep 1
done

# Banc d'essai de la compression : PDF aux images variées, rendu des pages avant/après
python3 ../.github/scripts/compress_test_pdf.py $OUT/compress-test.pdf
adb push $OUT/compress-test.pdf /data/local/tmp/compress-test.pdf
adb shell "cat /data/local/tmp/compress-test.pdf | run-as $PKG sh -c 'cat > files/compress-test.pdf'"
adb shell am start -n $PKG/.DevCompressActivity --es path /data/user/0/$PKG/files/compress-test.pdf
for _ in $(seq 1 60); do
  adb shell run-as $PKG test -f files/selftest/done && break
  sleep 2
done
check_crash "banc compression"
mkdir -p $OUT/compress
for f in $(adb shell run-as $PKG ls files/selftest | tr -d '\r'); do
  adb exec-out run-as $PKG cat "files/selftest/$f" > "$OUT/compress/$f"
done
echo "===== Compression ====="
cat $OUT/compress/report.txt || echo "pas de rapport"

adb shell pkill -INT screenrecord || true
wait $REC 2>/dev/null || true
sleep 3
adb pull /sdcard/parcours.mp4 $OUT/parcours.mp4 || true
adb logcat -d > $OUT/logcat.txt

if [ $crashed -ne 0 ]; then
  echo "===== Des plantages ont été détectés (voir ci-dessus) ====="
  exit 1
fi
echo "===== Aucun plantage ====="
