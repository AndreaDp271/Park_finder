#!/usr/bin/env bash
# Adatta il progetto generato da "npx cap add android": permessi di
# posizione, numero di versione e icone ParkMap al posto di quelle di Capacitor.
# Uso: customize-android.sh <numero_build>
set -euo pipefail
cd "$(dirname "$0")/android"
BUILD="${1:-1}"

MANIFEST=app/src/main/AndroidManifest.xml
# Posizione per il pallino GPS (la WebView di Capacitor chiede il permesso
# all'utente quando la pagina usa navigator.geolocation)
sed -i 's#<uses-permission android:name="android.permission.INTERNET" />#&\n    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />\n    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n    <uses-feature android:name="android.hardware.location.gps" android:required="false" />#' "$MANIFEST"
grep -q ACCESS_FINE_LOCATION "$MANIFEST" || { echo "permessi non inseriti nel manifest" >&2; exit 1; }

# Codice nativo di ParkMap (app/native): MainActivity con il plugin locale,
# widget "La mia auto", salvataggio dell'auto alla disconnessione dal
# Bluetooth e scorciatoie dell'icona. Sovrascrive la MainActivity generata.
cp -r ../native/java/. app/src/main/java/
cp -r ../native/res/. app/src/main/res/
python3 - "$MANIFEST" <<'PY'
import sys
path = sys.argv[1]
s = open(path, encoding='utf-8').read()
perms = '''    <!-- Salvataggio automatico dell'auto via Bluetooth: dispositivi associati
         (Android 12+), eventi di disconnessione (fino ad Android 11) e posizione
         letta con l'app in background -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    <!-- Aggiornamento dell'app dalle Releases di GitHub (installazione dell'APK scaricato) -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />

'''
block = '''
        <!-- Widget "La mia auto" -->
        <receiver
            android:name=".ParkMapWidget"
            android:exported="false"
            android:label="@string/parkmap_widget_label">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/parkmap_widget_info" />
        </receiver>

        <!-- Esito dell'installazione dell'aggiornamento (PackageInstaller) -->
        <receiver
            android:name=".UpdateReceiver"
            android:exported="false" />

        <!-- Connessione/disconnessione dal Bluetooth dell'auto: le invia lo stack Bluetooth,
             che ha un proprio utente di sistema, quindi exported="true" -->
        <receiver
            android:name=".BluetoothCarReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
                <action android:name="android.bluetooth.device.action.ACL_DISCONNECTED" />
            </intent-filter>
        </receiver>
'''
assert s.count('</application>') == 1 and s.count('<application') == 1
s = s.replace('</application>', block + '    </application>')
s = s.replace('<application', perms + '    <application', 1)
# Scorciatoie dell'icona sull'attività principale (quella con MAIN/LAUNCHER)
b = s.index('</activity>', s.index('android.intent.category.LAUNCHER'))
s = s[:b] + '    <meta-data android:name="android.app.shortcuts" android:resource="@xml/parkmap_shortcuts" />\n        ' + s[b:]
open(path, 'w', encoding='utf-8').write(s)
PY
for what in ParkMapWidget BluetoothCarReceiver UpdateReceiver REQUEST_INSTALL_PACKAGES android.app.shortcuts BLUETOOTH_CONNECT; do
  grep -q "$what" "$MANIFEST" || { echo "manifest: $what non inserito" >&2; exit 1; }
done

# Versione: cresce a ogni build, così Android accetta l'aggiornamento sopra la precedente
sed -i -E "s/versionCode [0-9]+/versionCode ${BUILD}/; s/versionName \"[^\"]*\"/versionName \"1.0.${BUILD}\"/" app/build.gradle
grep -q "versionCode ${BUILD}" app/build.gradle || { echo "versionCode non aggiornato" >&2; exit 1; }

# Icone e schermata di avvio, tutte generate da icons/icon.svg
# (icons/render-icons.js): legacy quadrata e tonda, adattiva (sfondo +
# primo piano nella zona sicura) e monocromatica per le icone a tema di Android 13+
command -v convert >/dev/null || { sudo apt-get update -q && sudo apt-get install -y -q imagemagick; }
ICONS=../../icons
RES=app/src/main/res
BRAND="#2563EB"
for pair in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  d=${pair%%:*}; px=${pair##*:}
  fg=$((px * 108 / 48))
  mkdir -p "$RES/mipmap-$d"
  convert "$ICONS/android-legacy.png" -resize "${px}x${px}" "$RES/mipmap-$d/ic_launcher.png"
  convert "$ICONS/android-round.png" -resize "${px}x${px}" "$RES/mipmap-$d/ic_launcher_round.png"
  convert "$ICONS/android-foreground.png" -resize "${fg}x${fg}" "$RES/mipmap-$d/ic_launcher_foreground.png"
  convert "$ICONS/android-background.png" -resize "${fg}x${fg}" "$RES/mipmap-$d/ic_launcher_background.png"
  convert "$ICONS/android-monochrome.png" -resize "${fg}x${fg}" "$RES/mipmap-$d/ic_launcher_monochrome.png"
done
# Icona piccola delle notifiche (promemoria sosta): sagoma bianca su trasparente
for pair in mdpi:24 hdpi:36 xhdpi:48 xxhdpi:72 xxxhdpi:96; do
  d=${pair%%:*}; px=${pair##*:}; inner=$((px * 84 / 100))
  mkdir -p "$RES/drawable-$d"
  convert "$ICONS/android-monochrome.png" -trim -resize "${inner}x${inner}" -background none -gravity center \
    -extent "${px}x${px}" "$RES/drawable-$d/ic_stat_parkmap.png"
done
for xml in "$RES/mipmap-anydpi-v26/ic_launcher.xml" "$RES/mipmap-anydpi-v26/ic_launcher_round.xml"; do
  cat > "$xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
XML
done

# Schermata di avvio: logo bianco su blu. Su Android 12+ il sistema mostra
# prima l'icona su windowSplashScreenBackground: stesso colore, nessuno stacco.
for f in $(find "$RES" -name 'splash.png'); do
  size=$(identify -format '%wx%h' "$f")
  w=${size%x*}; h=${size#*x}; m=$(( (w < h ? w : h) * 6 / 10 ))
  convert -size "$size" "xc:$BRAND" \( "$ICONS/splash-logo.png" -resize "${m}x${m}" \) -gravity center -composite "$f"
done
sed -i "s|<item name=\"android:background\">@drawable/splash</item>|&\n        <item name=\"windowSplashScreenBackground\">$BRAND</item>\n        <item name=\"windowSplashScreenAnimatedIcon\">@mipmap/ic_launcher_foreground</item>|" "$RES/values/styles.xml"
grep -q windowSplashScreenBackground "$RES/values/styles.xml" || { echo "tema di avvio non aggiornato" >&2; exit 1; }
echo "progetto Android personalizzato (build ${BUILD})"
