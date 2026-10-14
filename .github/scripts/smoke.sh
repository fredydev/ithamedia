#!/usr/bin/env bash
# Lance l'app sur l'émulateur et échoue si elle plante, en affichant la pile d'appels.
set -u
PKG=com.ithamedia.pdfsigner
mkdir -p smoke
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n $PKG/.HomeActivity
sleep 10
adb exec-out screencap -p > smoke/home.png
# Bascule sur l'onglet Mes fichiers (bas de l'écran, à droite) puis revient
adb shell input tap 810 2280 || true
sleep 3
adb exec-out screencap -p > smoke/files.png
adb logcat -d > smoke/logcat.txt
echo "===== AndroidRuntime ====="
adb logcat -d -s AndroidRuntime:E | tee smoke/crash.txt
if adb shell pidof $PKG > /dev/null; then
  echo "APP RUNNING"
else
  echo "APP NOT RUNNING"
fi
if grep -q "FATAL EXCEPTION" smoke/crash.txt; then
  exit 1
fi
