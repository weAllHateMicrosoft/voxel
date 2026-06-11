#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
#  play-mac.sh  —  launches DESCENT (macOS)
#  Run:  bash play-mac.sh   OR   double-click play-mac.command
# ─────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/game.jar"
REPO="yrdsb-peths/Final-dominic-arron"
JAR_URL="https://github.com/$REPO/releases/latest/download/game.jar"
JAVA_URL="https://adoptium.net/temurin/releases/?version=17&os=mac&arch=x64&package=jdk"

# ── 1. Check Java ─────────────────────────────────────────────────────────────
need_java() {
    echo ""
    echo "  Java 17 is required but was not found."
    echo ""
    # Auto-install via Homebrew if available (common on developer Macs)
    if command -v brew &>/dev/null; then
        echo "  Homebrew detected — installing Java 17 automatically..."
        brew install --cask temurin@17
        # Pick up the newly installed JVM
        export PATH="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null):$PATH"
        if command -v java &>/dev/null; then
            echo "  Java installed successfully. Continuing..."
            return 0
        fi
    fi
    echo "  Opening the Java download page in your browser..."
    open "$JAVA_URL" 2>/dev/null || true
    echo "  Install Java 17, then run this script again."
    echo ""
    read -p "  Press Enter to exit..."
    exit 1
}

if ! command -v java &>/dev/null; then
    need_java
fi

JAVA_MAJOR=$(java -version 2>&1 | grep -oE '"[0-9]+' | grep -oE '[0-9]+' | head -1)
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
    echo ""
    echo "  Java $JAVA_MAJOR is installed but Java 17+ is required."
    need_java
fi

# ── 2. Find / fetch the JAR ───────────────────────────────────────────────────
# Priority: root game.jar (committed or previously downloaded)
#           → download from GitHub releases
#           → build from source (cloned repo fallback)

if [ ! -f "$JAR" ]; then
    echo ""
    echo "  Downloading game..."
    if curl -fsSL "$JAR_URL" -o "$JAR.tmp" 2>/dev/null; then
        mv "$JAR.tmp" "$JAR"
        echo "  Download complete!"
    else
        rm -f "$JAR.tmp"
        # Fallback: build from Gradle source (works when the repo is cloned)
        if [ -f "$SCRIPT_DIR/gradlew" ]; then
            echo "  No download available — building from source (takes ~30 s)..."
            cd "$SCRIPT_DIR" && chmod +x gradlew && ./gradlew shadowJar --no-daemon -q
            LOCAL="$SCRIPT_DIR/build/libs/game.jar"
            [ -f "$LOCAL" ] && cp "$LOCAL" "$JAR" && echo "  Build complete!"
        fi
    fi
fi

if [ ! -f "$JAR" ]; then
    echo ""
    echo "  Could not find or build the game."
    echo "  If you cloned the repo, try:  ./gradlew shadowJar"
    echo ""
    read -p "  Press Enter to exit..."
    exit 1
fi

# ── 3. Launch ─────────────────────────────────────────────────────────────────
echo "  Launching DESCENT..."
echo ""
# -XstartOnFirstThread is required on macOS for LWJGL/GLFW
exec java -XstartOnFirstThread -jar "$JAR"
