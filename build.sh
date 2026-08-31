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

JTOKKIT_VERSION="1.1.0"
JTOKKIT_FINAL="$LIB_DIR/jtokkit-${JTOKKIT_VERSION}.jar"
# Gecici indirme adlari: ".jar" icermez, Windows bunlari tetiklemez
JTOKKIT_TMP="$LIB_DIR/.jtokkit-${JTOKKIT_VERSION}.download"

# sqlite-jdbc: VSCode state.vscdb okumak icin (P0, opt-in via chatsession.enabled).
# Pure Java jar, native libs icermiyor (org.xerial paketi icinde).
SQLITE_JDBC_VERSION="3.46.1.0"
SQLITE_JDBC_FINAL="$LIB_DIR/sqlite-jdbc-${SQLITE_JDBC_VERSION}.jar"
SQLITE_JDBC_TMP="$LIB_DIR/.sqlite-jdbc-${SQLITE_JDBC_VERSION}.download"

# gson: VSCode chat session JSON'larini okumak icin (P0). JSON ic ice
# array/object icerdigi icin projedeki duz regex JSON parser'i yetmiyor.
GSON_VERSION="2.11.0"
GSON_FINAL="$LIB_DIR/gson-${GSON_VERSION}.jar"
GSON_TMP="$LIB_DIR/.gson-${GSON_VERSION}.download"

# slf4j: sqlite-jdbc 3.46.x'in static initializer'i org.slf4j.LoggerFactory'a
# hard-reference ediyor (bundle Import-Package "resolution:=optional" demesine
# ragmen gercek kod kosulu kontrol etmiyor). slf4j-nop no-op binding ile
# tum log mesajlari sessizce yutulur.
SLF4J_VERSION="2.0.13"
SLF4J_API_FINAL="$LIB_DIR/slf4j-api-${SLF4J_VERSION}.jar"
SLF4J_API_TMP="$LIB_DIR/.slf4j-api-${SLF4J_VERSION}.download"
SLF4J_NOP_FINAL="$LIB_DIR/slf4j-nop-${SLF4J_VERSION}.jar"
SLF4J_NOP_TMP="$LIB_DIR/.slf4j-nop-${SLF4J_VERSION}.download"

MAVEN_REPO="https://repo1.maven.org/maven2/com/knuddels/jtokkit"
SQLITE_REPO="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc"
GSON_REPO="https://repo1.maven.org/maven2/com/google/code/gson/gson"
SLF4J_REPO="https://repo1.maven.org/maven2/org/slf4j"

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

# sqlite-jdbc: optional but downloaded unconditionally so users can flip
# chatsession.enabled=true without a second build pass.
if [[ -f "$SQLITE_JDBC_FINAL" ]]; then
    echo "OK sqlite-jdbc zaten mevcut, atlaniyor: $SQLITE_JDBC_FINAL"
else
    echo "==> sqlite-jdbc-${SQLITE_JDBC_VERSION} indiriliyor (.tmp uzantisi ile)"
    if ! curl -fL --retry 3 --connect-timeout 10 --silent --show-error \
        -o "$SQLITE_JDBC_TMP" \
        "${SQLITE_REPO}/${SQLITE_JDBC_VERSION}/sqlite-jdbc-${SQLITE_JDBC_VERSION}.jar"; then
        echo "X sqlite-jdbc indirilemedi. chatsession.enabled=true kullanmayacaksaniz sorun degil." >&2
        rm -f "$SQLITE_JDBC_TMP"
        # Non-fatal: feature is opt-in. Continue building with just jtokkit on classpath.
    else
        mv "$SQLITE_JDBC_TMP" "$SQLITE_JDBC_FINAL"
        SIZE=$(stat -c %s "$SQLITE_JDBC_FINAL" 2>/dev/null || stat -f %z "$SQLITE_JDBC_FINAL" 2>/dev/null || echo 0)
        if [[ "$SIZE" -lt 1000000 ]]; then
            echo "X sqlite-jdbc dosyasi cok kucuk ($SIZE bytes). Muhtemelen hatali." >&2
            rm -f "$SQLITE_JDBC_FINAL"
        else
            echo "OK sqlite-jdbc indirildi ($SIZE bytes)"
        fi
    fi
fi

# gson: VSCode chat session JSON parsing. Like sqlite-jdbc, downloaded
# unconditionally so feature toggling is just a config flag flip.
if [[ -f "$GSON_FINAL" ]]; then
    echo "OK gson zaten mevcut, atlaniyor: $GSON_FINAL"
else
    echo "==> gson-${GSON_VERSION} indiriliyor (.tmp uzantisi ile)"
    if ! curl -fL --retry 3 --connect-timeout 10 --silent --show-error \
        -o "$GSON_TMP" \
        "${GSON_REPO}/${GSON_VERSION}/gson-${GSON_VERSION}.jar"; then
        echo "X gson indirilemedi. chatsession.enabled=true kullanmayacaksaniz sorun degil." >&2
        rm -f "$GSON_TMP"
    else
        mv "$GSON_TMP" "$GSON_FINAL"
        SIZE=$(stat -c %s "$GSON_FINAL" 2>/dev/null || stat -f %z "$GSON_FINAL" 2>/dev/null || echo 0)
        if [[ "$SIZE" -lt 100000 ]]; then
            echo "X gson dosyasi cok kucuk ($SIZE bytes). Muhtemelen hatali." >&2
            rm -f "$GSON_FINAL"
        else
            echo "OK gson indirildi ($SIZE bytes)"
        fi
    fi
fi

# slf4j: sqlite-jdbc transitive runtime dep. nop binding suppresses logs.
download_jar() {
    local final="$1" tmp="$2" url="$3" min_size="$4" label="$5"
    if [[ -f "$final" ]]; then
        echo "OK ${label} zaten mevcut, atlaniyor: $final"
        return 0
    fi
    echo "==> ${label} indiriliyor (.tmp uzantisi ile)"
    if ! curl -fL --retry 3 --connect-timeout 10 --silent --show-error \
        -o "$tmp" "$url"; then
        echo "X ${label} indirilemedi." >&2
        rm -f "$tmp"
        return 1
    fi
    mv "$tmp" "$final"
    local size
    size=$(stat -c %s "$final" 2>/dev/null || stat -f %z "$final" 2>/dev/null || echo 0)
    if [[ "$size" -lt "$min_size" ]]; then
        echo "X ${label} dosyasi cok kucuk ($size bytes)." >&2
        rm -f "$final"
        return 1
    fi
    echo "OK ${label} indirildi ($size bytes)"
}

download_jar "$SLF4J_API_FINAL" "$SLF4J_API_TMP" \
    "${SLF4J_REPO}/slf4j-api/${SLF4J_VERSION}/slf4j-api-${SLF4J_VERSION}.jar" \
    30000 "slf4j-api ${SLF4J_VERSION}" || true
download_jar "$SLF4J_NOP_FINAL" "$SLF4J_NOP_TMP" \
    "${SLF4J_REPO}/slf4j-nop/${SLF4J_VERSION}/slf4j-nop-${SLF4J_VERSION}.jar" \
    1000 "slf4j-nop ${SLF4J_VERSION}" || true

echo "==> Kaynak kodlar derleniyor"
mkdir -p "$OUT_DIR"

# Classpath: jtokkit her zaman; sqlite-jdbc + gson + slf4j P0 icin.
CP="$JTOKKIT_FINAL"
[[ -f "$SQLITE_JDBC_FINAL" ]] && CP="$CP:$SQLITE_JDBC_FINAL"
[[ -f "$GSON_FINAL" ]] && CP="$CP:$GSON_FINAL"
[[ -f "$SLF4J_API_FINAL" ]] && CP="$CP:$SLF4J_API_FINAL"
[[ -f "$SLF4J_NOP_FINAL" ]] && CP="$CP:$SLF4J_NOP_FINAL"

# Use bash's globstar instead of `find` — avoids Windows' find.exe
# shadowing the GNU one when System32 appears earlier in PATH.
shopt -s globstar nullglob
SOURCES=("$SRC_DIR"/**/*.java)

"$JAVAC" -d "$OUT_DIR" \
         -cp "$CP" \
         --release "$RELEASE_TARGET" \
         -encoding UTF-8 \
         -Xlint:all \
         "${SOURCES[@]}"

echo "OK Derleme basarili"
echo ""
echo "Cikti: $OUT_DIR/"
echo ""
echo "Test calistirma:"
echo "  $PROJECT_ROOT/copilot-lens.sh --help"
echo ""
echo "Sistem PATH'ine eklemek icin: ./install.sh"
