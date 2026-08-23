#!/usr/bin/env bash
# build.sh - copilot-lens derleme scripti
# JDK 17+ gerekir. jtokkit'i Maven Central'dan jar olarak indirir.
# Jar dosyasini gizli ara adla indirir (Windows file-association prompt'unu onler),
# sonra .jar uzantisi ile yeniden adlandirir.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_ROOT"

LIB_DIR="$PROJECT_ROOT/lib"
SRC_DIR="$PROJECT_ROOT/src"
OUT_DIR="$PROJECT_ROOT/out"

JTOKKIT_VERSION="0.6.1"
JTOKKIT_FINAL="$LIB_DIR/jtokkit-${JTOKKIT_VERSION}.jar"
# Gecici indirme adlari: ".jar" icermez, Windows bunlari tetiklemez
JTOKKIT_TMP="$LIB_DIR/.jtokkit-${JTOKKIT_VERSION}.download"

MAVEN_REPO="https://repo1.maven.org/maven2/com/knuddels/jtokkit"

echo "==> JDK kontrol"
if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/javac" ]]; then
    JAVAC="$JAVA_HOME/bin/javac"
elif command -v javac >/dev/null 2>&1; then
    JAVAC="javac"
else
    echo "X javac bulunamadi. JDK 17+ kurulu olmali." >&2
    exit 1
fi

JAVA_VERSION=$("$JAVAC" -version 2>&1 | head -1 | awk '{print $2}')
echo "OK $JAVA_VERSION (${JAVAC})"

if "$JAVAC" --release 21 -version >/dev/null 2>&1; then
    RELEASE_TARGET=21
elif "$JAVAC" --release 17 -version >/dev/null 2>&1; then
    RELEASE_TARGET=17
else
    echo "X JDK 17+ gerekli." >&2
    exit 1
fi
echo "OK --release $RELEASE_TARGET"

echo "==> lib/ dizini"
mkdir -p "$LIB_DIR"

if [[ -f "$JTOKKIT_FINAL" ]]; then
    echo "OK jtokkit zaten mevcut, atlaniyor: $JTOKKIT_FINAL"
else
    echo "==> jtokkit-${JTOKKIT_VERSION} indiriliyor (.tmp uzantisi ile)"
    # 1) Gecici .download uzantisi ile indir (.jar tetiklemez)
    if ! curl -fL --retry 3 --connect-timeout 10 --silent --show-error \
        -o "$JTOKKIT_TMP" \
        "${MAVEN_REPO}/${JTOKKIT_VERSION}/jtokkit-${JTOKKIT_VERSION}.jar"; then
        echo "X Indirme basarisiz. Internet baglantisini kontrol edin." >&2
        rm -f "$JTOKKIT_TMP"
        exit 1
    fi

    # 2) Atomik rename: .download -> .jar (Windows sadece son islemi gorur)
    mv "$JTOKKIT_TMP" "$JTOKKIT_FINAL"

    # 3) Boyut dogrulamasi (bos/kucuk dosyayi reddet)
    SIZE=$(stat -c %s "$JTOKKIT_FINAL" 2>/dev/null || stat -f %z "$JTOKKIT_FINAL" 2>/dev/null || echo 0)
    if [[ "$SIZE" -lt 100000 ]]; then
        echo "X Indirilen dosya cok kucuk ($SIZE bytes). Muhtemelen hatali." >&2
        rm -f "$JTOKKIT_FINAL"
        exit 1
    fi
    echo "OK jtokkit indirildi ($SIZE bytes)"
fi

echo "==> Kaynak kodlar derleniyor"
mkdir -p "$OUT_DIR"

SOURCES=$(find "$SRC_DIR" -name "*.java")

"$JAVAC" -d "$OUT_DIR" \
         -cp "$JTOKKIT_FINAL" \
         --release "$RELEASE_TARGET" \
         -Xlint:all \
         $SOURCES

echo "OK Derleme basarili"
echo ""
echo "Cikti: $OUT_DIR/"
echo ""
echo "Test calistirma:"
echo "  $PROJECT_ROOT/copilot-lens.sh --help"
echo ""
echo "Sistem PATH'ine eklemek icin: ./install.sh"
