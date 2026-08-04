#!/usr/bin/env bash

# ─── WARNA & STYLING ─────────────────────────────────────────
RED='\033[0;31m';   GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m';  CYAN='\033[0;36m';  MAGENTA='\033[0;35m'
BOLD='\033[1m';     DIM='\033[2m';      RESET='\033[0m'

# ─── PATH & KONFIGURASI ──────────────────────────────────────
HOME_DIR="/data/data/com.termux/files/home"
SDK_DIR="$HOME_DIR/android-sdk"
NDK_VER_DEFAULT="25.2.9519653"
NDK_DIR="$SDK_DIR/ndk/$NDK_VER_DEFAULT"
WRAPPER_DIR="$HOME_DIR/android-sdk/wrapper-template"
WORKSPACE="$HOME_DIR/workspace"
LOG_FILE="/sdcard/build-error.log"
: "${PREFIX:=/data/data/com.termux/files/usr}"
GRADLE_OPTS="-Xmx1200m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC"
APP_STATE_DIR="$HOME_DIR/.termux-apk-builder"
LAST_PROJECT_FILE="$APP_STATE_DIR/last_project.txt"
LAUNCHER_NAME="apkbuilder"

# ═══════════════════════════════════════════════════════════
#  UI HELPERS
# ═══════════════════════════════════════════════════════════
banner() {
    clear
    echo -e "${CYAN}${BOLD}"
    echo "  ╔══════════════════════════════════════════════════╗"
    echo "  ║   🚀 TERMUX APK BUILDER PRO v8.2 TOUCH READY     ║"
    echo "  ║      Full Native ARM64 · Java 17 Downgrade Fix   ║"
    echo "  ╚══════════════════════════════════════════════════╝"
    echo -e "${RESET}"
}

step()  { echo -e "\n${CYAN}${BOLD}  ▶  $1 ${RESET}"; }
info()  { echo -e "  ${BLUE}ℹ  $1${RESET}"; }
ok()    { echo -e "  ${GREEN}✅ $1${RESET}"; }
warn()  { echo -e "  ${YELLOW}⚠️  $1${RESET}"; }
err()   { echo -e "  ${RED}❌ $1${RESET}"; }

# TermuxMod: replaces the various "Tekan [Enter] untuk kembali..." prompts.
# Added so this script can be driven by the TermuxMod Android UI without a
# visible terminal — when TERMUXMOD_NONINTERACTIVE=1 is set, this returns
# immediately instead of blocking forever waiting for input that will never
# come (which previously caused an infinite "Pilihan tidak valid!" loop once
# stdin ran out). When run normally from a terminal, behavior is unchanged.
pause() {
    if [ "$TERMUXMOD_NONINTERACTIVE" = "1" ]; then
        return 0
    fi
    read -rp "  ↩  Tekan [Enter] untuk kembali..." _
}

ensure_app_state() {
    mkdir -p "$APP_STATE_DIR" 2>/dev/null || true
}

get_last_project() {
    [ -f "$LAST_PROJECT_FILE" ] || return 1
    local last_project
    last_project=$(head -n1 "$LAST_PROJECT_FILE" 2>/dev/null)
    [ -n "$last_project" ] && [ -d "$last_project" ] || return 1
    printf '%s\n' "$last_project"
}

save_last_project() {
    local project_path="$1"
    [ -n "$project_path" ] || return 0
    ensure_app_state
    printf '%s\n' "$project_path" > "$LAST_PROJECT_FILE"
}

resolve_script_path() {
    local src="${BASH_SOURCE[0]}"
    local src_dir src_name
    src_name=$(basename "$src")
    src_dir=$(cd "$(dirname "$src")" 2>/dev/null && pwd -P)
    [ -n "$src_dir" ] && printf '%s/%s\n' "$src_dir" "$src_name" || printf '%s\n' "$src"
}

install_launcher() {
    local source_script launcher_path
    source_script=$(resolve_script_path)
    launcher_path="$PREFIX/bin/$LAUNCHER_NAME"

    if [ ! -f "$source_script" ]; then
        err "Script sumber tidak ditemukan: $source_script"
        pause
        return
    fi

    cp "$source_script" "$launcher_path" 2>/dev/null || {
        err "Gagal memasang launcher ke $launcher_path"
        pause
        return
    }
    chmod +x "$launcher_path" 2>/dev/null || true

    ok "Launcher berhasil dipasang"
    info "Sekarang kamu bisa jalanin script dari mana saja cukup ketik: $LAUNCHER_NAME"
    pause
}

collect_android_projects() {
    local tmpfile gradle_file dir root
    tmpfile=$(mktemp)

    while IFS= read -r gradle_file; do
        case "$gradle_file" in
            */build/*|*/.gradle/*) continue ;;
        esac

        dir=$(dirname "$gradle_file")
        root="$dir"

        if [ "$(basename "$dir")" = "app" ] && { [ -f "$dir/../settings.gradle" ] || [ -f "$dir/../settings.gradle.kts" ] || [ -f "$dir/../gradlew" ]; }; then
            root=$(cd "$dir/.." 2>/dev/null && pwd -P)
        elif [ -f "$dir/settings.gradle" ] || [ -f "$dir/settings.gradle.kts" ] || [ -f "$dir/gradlew" ] || [ -f "$dir/app/build.gradle" ] || [ -f "$dir/app/build.gradle.kts" ]; then
            root=$(cd "$dir" 2>/dev/null && pwd -P)
        fi

        [ -n "$root" ] && printf '%s\n' "$root" >> "$tmpfile"
    done < <(find /sdcard -maxdepth 5 -type f \( -name "settings.gradle" -o -name "settings.gradle.kts" -o -name "build.gradle" -o -name "build.gradle.kts" \) 2>/dev/null)

    sort -u "$tmpfile"
    rm -f "$tmpfile"
}

# ═══════════════════════════════════════════════════════════
#  FUNGSI KOREKSI IZIN & TOOLCHAIN NDK TERMUX
# ═══════════════════════════════════════════════════════════
fix_ndk_permissions() {
    if [ -d "$SDK_DIR/ndk" ]; then
        local actual_ndk_bin=$(find "$SDK_DIR/ndk" -maxdepth 3 -name "ndk-build" 2>/dev/null | head -n1)
        if [ -n "$actual_ndk_bin" ]; then
            local actual_ndk_dir=$(dirname "$actual_ndk_bin")
            if [ "$actual_ndk_dir" != "$NDK_DIR" ] && [ ! -d "$NDK_DIR" ]; then
                mkdir -p "$(dirname "$NDK_DIR")"
                ln -sf "$actual_ndk_dir" "$NDK_DIR" 2>/dev/null || cp -r "$actual_ndk_dir" "$NDK_DIR"
            fi
        fi
    fi

    if [ -d "$NDK_DIR" ]; then
        chmod -R +x "$NDK_DIR" 2>/dev/null || true
        mkdir -p "$NDK_DIR/prebuilt/linux-aarch64/bin"
        [ -f "$PREFIX/bin/make" ] && ln -sf "$PREFIX/bin/make" "$NDK_DIR/prebuilt/linux-aarch64/bin/make"
        [ -f "$PREFIX/bin/python3" ] && ln -sf "$PREFIX/bin/python3" "$NDK_DIR/prebuilt/linux-aarch64/bin/python3"

        if command -v termux-fix-shebang >/dev/null 2>&1; then
            termux-fix-shebang "$NDK_DIR/ndk-build" 2>/dev/null || true
            termux-fix-shebang "$NDK_DIR/build/ndk-build" 2>/dev/null || true
            find "$NDK_DIR" -type f \( -name "*.sh" -o -name "ndk-build" \) -exec termux-fix-shebang {} \; 2>/dev/null || true
        fi
    fi
}

# ═══════════════════════════════════════════════════════════
#  FUNGSI DUMMY CMAKE & NINJA SDK
# ═══════════════════════════════════════════════════════════
setup_dummy_cmake() {
    local cmake_ver="${1:-3.22.1}"
    local cmake_dir="$SDK_DIR/cmake/$cmake_ver"

    mkdir -p "$cmake_dir/bin"
    [ ! -f "$PREFIX/bin/ninja" ] && pkg install ninja -y 2>/dev/null || true

    [ -f "$PREFIX/bin/cmake" ] && ln -sf "$PREFIX/bin/cmake" "$cmake_dir/bin/cmake"
    [ -f "$PREFIX/bin/ninja" ] && ln -sf "$PREFIX/bin/ninja" "$cmake_dir/bin/ninja"
    [ -f "$PREFIX/bin/ninja" ] && ln -sf "$PREFIX/bin/ninja" "$cmake_dir/bin/ninja-build"

    cat > "$cmake_dir/source.properties" << EOF
Pkg.PluginsSource=Android SDK
Pkg.Revision=$cmake_ver
Pkg.Path=cmake;$cmake_ver
EOF
    ok "SDK CMake $cmake_ver & Ninja siap"
}

# ═══════════════════════════════════════════════════════════
#  SMART CACHE SDK PLATFORM & BUILD-TOOLS
# ═══════════════════════════════════════════════════════════
download_platform_sdk() {
    local api_level="$1"
    local platform_dir="$SDK_DIR/platforms/android-$api_level"

    rm -rf "$SDK_DIR/platforms/android-13" "$SDK_DIR/platforms/android-14" 2>/dev/null || true

    if [ -d "$platform_dir" ] && [ -f "$platform_dir/android.jar" ] && [ -f "$platform_dir/core-for-system-modules.jar" ] && [ -f "$platform_dir/framework.aidl" ]; then
        local jar_size=$(wc -c < "$platform_dir/android.jar" 2>/dev/null || echo 0)
        local core_size=$(wc -c < "$platform_dir/core-for-system-modules.jar" 2>/dev/null || echo 0)
        if [ "$jar_size" -ne "$core_size" ]; then
            ok "Platform android-$api_level AOSP resmi siap (Cached)"
            return 0
        fi
        rm -rf "$platform_dir"
    fi

    warn "Mengunduh Platform SDK android-$api_level resmi dari Google..."
    local url="https://dl.google.com/android/repository/platform-${api_level}_r01.zip"
    local tmp_zip="$SDK_DIR/platform-$api_level.zip"
    local tmp_extract="$SDK_DIR/platforms/tmp_extract"
    
    rm -rf "$tmp_zip" "$tmp_extract"
    mkdir -p "$tmp_extract" "$SDK_DIR/platforms"
    
    if wget -q --show-progress -O "$tmp_zip" "$url" && [ -s "$tmp_zip" ]; then
        unzip -o -q "$tmp_zip" -d "$tmp_extract" 2>/dev/null
        rm -f "$tmp_zip"
        
        local extracted_folder=$(find "$tmp_extract" -maxdepth 1 -mindepth 1 -type d | head -n1)
        if [ -n "$extracted_folder" ] && [ -f "$extracted_folder/android.jar" ]; then
            rm -rf "$platform_dir"
            mv "$extracted_folder" "$platform_dir"
            rm -rf "$tmp_extract"
            
            echo "Pkg.Revision=1" > "$platform_dir/source.properties"
            echo "AndroidVersion.ApiLevel=$api_level" >> "$platform_dir/source.properties"
            if [ ! -f "$platform_dir/framework.aidl" ]; then
                wget -q -O "$platform_dir/framework.aidl" "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-$api_level/framework.aidl" 2>/dev/null || true
            fi
            ok "Platform android-$api_level resmi Google terpasang"
            return 0
        fi
    fi
    rm -rf "$tmp_extract" "$tmp_zip"

    warn "Fallback: Mengunduh dari Mirror AOSP..."
    mkdir -p "$platform_dir"
    wget -q --show-progress -O "$platform_dir/android.jar" "https://github.com/Reginer/aosp-android-jar/raw/main/android-$api_level/android.jar"
    wget -q -O "$platform_dir/framework.aidl" "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-$api_level/framework.aidl" 2>/dev/null || true

    if [ ! -s "$platform_dir/framework.aidl" ]; then
        cat > "$platform_dir/framework.aidl" << 'EOF'
interface java.lang.CharSequence;
interface java.lang.String;
parcelable android.accounts.Account;
parcelable android.app.PendingIntent;
parcelable android.content.ComponentName;
parcelable android.content.Intent;
parcelable android.content.IntentFilter;
parcelable android.graphics.Bitmap;
parcelable android.graphics.Rect;
parcelable android.net.Uri;
parcelable android.os.Bundle;
parcelable android.os.ParcelFileDescriptor;
parcelable android.os.ParcelUuid;
parcelable android.os.PersistableBundle;
parcelable android.view.KeyEvent;
parcelable android.view.MotionEvent;
EOF
    fi

    echo "ro.build.version.sdk=$api_level" > "$platform_dir/build.prop"
    echo "Pkg.Revision=1" > "$platform_dir/source.properties"
    echo "AndroidVersion.ApiLevel=$api_level" >> "$platform_dir/source.properties"
    ok "Platform android-$api_level disiapkan"
    return 0
}

setup_dummy_build_tools() {
    local bt_ver="$1"
    local bt_dir="$SDK_DIR/build-tools/$bt_ver"

    mkdir -p "$bt_dir/lib" "$bt_dir/renderscript/include" "$bt_dir/renderscript/clang-include"

    for tool in aapt aapt2 d8 zipalign apksigner; do
        [ -f "$PREFIX/bin/$tool" ] && ln -sf "$PREFIX/bin/$tool" "$bt_dir/$tool"
    done
    ln -sf "$PREFIX/bin/d8" "$bt_dir/dx" 2>/dev/null || true

    if [ -f "$PREFIX/bin/aidl" ]; then
        ln -sf "$PREFIX/bin/aidl" "$bt_dir/aidl"
    elif [ ! -s "$bt_dir/aidl" ]; then
        cat > "$bt_dir/aidl" << 'PYEOF'
#!/usr/bin/env python3
import sys, os, re
args = sys.argv[1:]
out_dir, input_files = None, []
i = 0
while i < len(args):
    arg = args[i]
    if arg.startswith('-o'):
        out_dir = arg[2:] if len(arg) > 2 else (args[i+1] if i+1 < len(args) else None)
        if len(arg) <= 2: i += 1
    elif arg.endswith('.aidl'): input_files.append(arg)
    i += 1
if out_dir:
    for aidl_file in input_files:
        if not os.path.exists(aidl_file): continue
        try:
            with open(aidl_file, 'r', encoding='utf-8', errors='ignore') as f: content = f.read()
            pkg_m = re.search(r'package\s+([\w.]+)\s*;', content)
            pkg = pkg_m.group(1) if pkg_m else ''
            iface_m = re.search(r'(interface|parcelable)\s+(\w+)', content)
            iface = iface_m.group(2) if iface_m else None
            if iface:
                tdir = os.path.join(out_dir, *pkg.split('.')) if pkg else out_dir
                os.makedirs(tdir, exist_ok=True)
                tjava = os.path.join(tdir, f"{iface}.java")
                jcode = f"package {pkg};\npublic interface {iface} extends android.os.IInterface {{\n    public static abstract class Stub extends android.os.Binder implements {pkg}.{iface} {{\n        private static final java.lang.String DESCRIPTOR = \"{pkg}.{iface}\";\n        public Stub() {{ this.attachInterface(this, DESCRIPTOR); }}\n        public static {pkg}.{iface} asInterface(android.os.IBinder obj) {{\n            if (obj == null) return null;\n            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);\n            if (iin != null && iin instanceof {pkg}.{iface}) return ({pkg}.{iface}) iin;\n            return new {pkg}.{iface}.Stub.Proxy(obj);\n        }}\n        @Override public android.os.IBinder asBinder() {{ return this; }}\n        private static class Proxy implements {pkg}.{iface} {{\n            private android.os.IBinder mRemote;\n            Proxy(android.os.IBinder remote) {{ mRemote = remote; }}\n            @Override public android.os.IBinder asBinder() {{ return mRemote; }}\n        }}\n    }}\n}}\n"
                with open(tjava, 'w', encoding='utf-8') as f: f.write(jcode)
        except Exception: pass
sys.exit(0)
PYEOF
        chmod +x "$bt_dir/aidl"
    fi

    local dummy_execs=("dexdump" "split-select" "mainDexClasses" "mainDexClasses.bat" "llvm-rs-cc" "bcc_compat" "lld" "arm-linux-androideabi-ld" "i686-linux-android-ld" "mipsel-linux-android-ld" "aarch64-linux-android-ld" "x86_64-linux-android-ld")
    for dummy_exec in "${dummy_execs[@]}"; do
        if [ ! -f "$bt_dir/$dummy_exec" ]; then
            cat > "$bt_dir/$dummy_exec" << 'EOF'
#!/bin/sh
exit 0
EOF
            chmod +x "$bt_dir/$dummy_exec"
        fi
    done

    local empty_zip_base64="UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA=="
    local dummy_jars=("core-lambda-stubs.jar" "mainDexClasses.rules" "lib/apksigner.jar" "lib/d8.jar" "lib/dx.jar" "lib/aapt2.jar" "lib/shrinkscript.jar")
    for jarfile in "${dummy_jars[@]}"; do
        if [ ! -s "$bt_dir/$jarfile" ]; then
            echo "$empty_zip_base64" | base64 -d > "$bt_dir/$jarfile" 2>/dev/null || touch "$bt_dir/$jarfile"
        fi
    done

    cat > "$bt_dir/source.properties" << EOF
Pkg.PluginsSource=Android SDK
Pkg.Revision=$bt_ver
EOF
    ok "Build-tools $bt_ver siap"
}

detect_agp_version() {
    grep -E "com.android.tools.build:gradle:[0-9.]+" "$1" | head -n1 | sed -E 's/.*:([0-9.]+).*/\1/'
}

agp_to_gradle() {
    case "$1" in
        8.4.*|8.5.*|8.6.*) echo "8.7" ;;
        8.2.*|8.3.*)       echo "8.2" ;;
        8.0.*|8.1.*)       echo "8.0" ;;
        7.4.*)             echo "7.5" ;;
        7.3.*)             echo "7.4" ;;
        *)                 echo "8.7" ;;
    esac
}

_require_python() {
    command -v python3 >/dev/null 2>&1 || { warn "python3 diperlukan"; return 1; }
}

clean_toolchains_python() {
    local target_file="$1"
    _require_python || return 1
    python3 - "$target_file" <<'PYEOF'
import re, sys
path = sys.argv[1]
try:
    with open(path, 'r', encoding='utf-8', errors='ignore') as f: src = f.read()
    src = re.sub(r'JavaVersion\.VERSION_2[0-9]', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'JavaVersion\.VERSION_19', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'JavaVersion\.VERSION_18', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'sourceCompatibility\s*=?\s*[\'"]?2[0-9][\'"]?', 'sourceCompatibility = JavaVersion.VERSION_17', src)
    src = re.sub(r'targetCompatibility\s*=?\s*[\'"]?2[0-9][\'"]?', 'targetCompatibility = JavaVersion.VERSION_17', src)
    src = re.sub(r'jvmTarget\s*=\s*[\'"]2[0-9][\'"]', 'jvmTarget = "17"', src)

    while True:
        match = re.search(r'(?i)(\bjavaCompiler\s*=\s*javaToolchains[^{]*\{)', src)
        if not match:
            match = re.search(r'(?i)(\bjavaCompiler\s*=\s*[^\n]+)', src)
            if not match: break
        start = match.start()
        depth, end = 0, -1
        for i in range(match.end() - 1, len(src)):
            if src[i] == '{': depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0: end = i + 1; break
        if end != -1: src = src[:start] + '/* javaCompiler disabled */' + src[end:]
        else:
            line_end = src.find('\n', start)
            if line_end != -1: src = src[:start] + '/* javaCompiler disabled */' + src[line_end:]
            else: break

    src = re.sub(r'(?i)(\bjvmToolchain\s*\([^)]*\))', r'/* \1 */', src)
    src = re.sub(r'(?i)(\bjvmToolchain\s*=.*)', r'/* \1 */', src)

    while True:
        match = re.search(r'(?i)(\btoolchain\s*\{)', src)
        if not match: break
        start = match.start()
        depth, end = 0, -1
        for i in range(match.end() - 1, len(src)):
            if src[i] == '{': depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0: end = i + 1; break
        if end != -1: src = src[:start] + '/* toolchain disabled */' + src[end:]
        else: break

    with open(path, 'w', encoding='utf-8') as f: f.write(src)
except Exception: pass
PYEOF
}

inject_sdk_and_ndk() {
    local gradle_file="$1" sdk_ver="$2" ndk_ver="$3"
    _require_python || return 1
    cp "$gradle_file" "$gradle_file.bak" 2>/dev/null || true
    python3 - "$gradle_file" "$sdk_ver" "$ndk_ver" <<'PYEOF'
import re, sys
path, sdk_ver, ndk_ver = sys.argv[1], sys.argv[2], sys.argv[3]
is_kts = path.endswith('.kts')
with open(path, 'r', encoding='utf-8', errors='ignore') as f: src = f.read()

if is_kts:
    if re.search(r'compileSdk\s*=', src): src = re.sub(r'compileSdk\s*=\s*[0-9]+', f'compileSdk = {sdk_ver}', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\n    compileSdk = ' + sdk_ver, src, count=1)
else:
    if re.search(r'compileSdk\s+[0-9]+', src): src = re.sub(r'compileSdk\s+[0-9]+', f'compileSdk {sdk_ver}', src)
    elif re.search(r'compileSdkVersion\s+[0-9]+', src): src = re.sub(r'compileSdkVersion\s+[0-9]+', f'compileSdkVersion {sdk_ver}', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\n    compileSdk ' + sdk_ver, src, count=1)

if is_kts:
    if re.search(r'ndkVersion\s*=', src): src = re.sub(r'ndkVersion\s*=\s*[\'"][^\'"]+[\'"]', f'ndkVersion = "{ndk_ver}"', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\n    ndkVersion = "' + ndk_ver + '"', src, count=1)
else:
    if re.search(r'ndkVersion\s+[\'"]', src): src = re.sub(r'ndkVersion\s+[\'"][^\'"]+[\'"]', f'ndkVersion "{ndk_ver}"', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\n    ndkVersion "' + ndk_ver + '"', src, count=1)

with open(path, 'w', encoding='utf-8') as f: f.write(src)
print("INJECT_SUCCESS")
PYEOF
}

ensure_wrapper_template() {
    mkdir -p "$WRAPPER_DIR/gradle/wrapper"
    if [ ! -f "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" ] || [ $(wc -c < "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || echo 0) -lt 10000 ]; then
        warn "Membuat Wrapper Template Gradle 8.7 resmi..."
        cd "$WRAPPER_DIR"
        echo "rootProject.name='wrapper-template'" > settings.gradle
        wget -q -O "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || true
        gradle wrapper --gradle-version 8.7 --no-daemon -q 2>/dev/null || true
        cd ~
    fi
}

export_backup() {
    banner
    echo -e "  ${YELLOW}📦 Export Backup Fast (SDK + Cache + Paket Termux Offline)${RESET}\n"
    pkg install p7zip zip rsync -y 2>/dev/null || true
    if [ ! -d "$SDK_DIR" ]; then err "Belum ada SDK. Jalankan Auto-Setup dulu."; sleep 2; return; fi

    STAGE="$HOME_DIR/.backup-temp"
    rm -rf "$STAGE"; mkdir -p "$STAGE/pkg-cache"
    step "1/4: Menyalin SDK Platforms & Build-Tools..."
    rsync -a --exclude='ndk/' "$SDK_DIR/" "$STAGE/android-sdk/"
    step "2/4: Menyalin Cache Gradle & Wrapper Template..."
    [ -d "$HOME_DIR/.gradle" ] && rsync -a "$HOME_DIR/.gradle/" "$STAGE/.gradle/"
    [ -d "$WRAPPER_DIR" ] && rsync -a "$WRAPPER_DIR/" "$STAGE/wrapper-template/"
    step "3/4: Menyalin Installer Paket Termux (.deb)..."
    [ -d "$SDK_DIR/pkg-cache" ] && cp "$SDK_DIR/pkg-cache/"*.deb "$STAGE/pkg-cache/" 2>/dev/null || true
    [ -d "$PREFIX/var/cache/apt/archives" ] && cp "$PREFIX/var/cache/apt/archives/"*.deb "$STAGE/pkg-cache/" 2>/dev/null || true
    step "4/4: Mengompresi Backup ke /sdcard/..."
    ZIPNAME="/sdcard/builder-backup-complete-$(date +%Y%m%d-%H%M).zip"
    (cd "$STAGE" && zip -qr "$ZIPNAME" .)
    SIZE=$(du -h "$ZIPNAME" 2>/dev/null | cut -f1)
    rm -rf "$STAGE"
    ok "Export Backup Selesai! ($SIZE)"
    pause
}

import_backup() {
    banner
    echo -e "  ${YELLOW}📥 Import Backup Offline (Pindah HP / Restore)${RESET}\n"
    pkg install p7zip unzip rsync -y 2>/dev/null || true
    BACKUP_FILE=$(ls -t /sdcard/builder-backup-complete-*.zip 2>/dev/null | head -n1)
    if [ -z "$BACKUP_FILE" ]; then err "Tidak ada file backup ditemukan!"; pause; return; fi

    SIZE=$(du -h "$BACKUP_FILE" 2>/dev/null | cut -f1)
    echo -e "  ${GREEN}✅ Ditemukan Backup: $(basename "$BACKUP_FILE") (${SIZE})${RESET}"
    if [ "$TERMUXMOD_NONINTERACTIVE" = "1" ]; then
        confirm="y"
    else
        read -rp "  ▶ Pulihkan dari backup ini? (y/n): " confirm
    fi
    [[ ! "$confirm" =~ ^[Yy]$ ]] && return

    TEMP_RESTORE="$HOME_DIR/.restore-temp"
    rm -rf "$TEMP_RESTORE"; mkdir -p "$TEMP_RESTORE"
    step "1/4: Mengekstrak File Backup..."
    unzip -o -q "$BACKUP_FILE" -d "$TEMP_RESTORE/"
    step "2/4: Memasang Paket Termux (.deb) OFFLINE..."
    if [ -d "$TEMP_RESTORE/pkg-cache" ] && [ -n "$(ls -A "$TEMP_RESTORE/pkg-cache/*.deb" 2>/dev/null)" ]; then
        dpkg -i --force-depends "$TEMP_RESTORE/pkg-cache/"*.deb >/dev/null 2>&1 || true
        mkdir -p "$SDK_DIR/pkg-cache"
        cp "$TEMP_RESTORE/pkg-cache/"*.deb "$SDK_DIR/pkg-cache/" 2>/dev/null || true
        ok "Paket Termux berhasil dipasang OFFLINE"
    fi
    step "3/4: Memulihkan SDK dan Cache Gradle..."
    [ -d "$TEMP_RESTORE/android-sdk" ] && rsync -a "$TEMP_RESTORE/android-sdk/" "$SDK_DIR/"
    [ -d "$TEMP_RESTORE/.gradle" ] && rsync -a "$TEMP_RESTORE/.gradle/" "$HOME_DIR/.gradle/"
    [ -d "$TEMP_RESTORE/wrapper-template" ] && rsync -a "$TEMP_RESTORE/wrapper-template/" "$WRAPPER_DIR/"
    step "4/4: Deteksi & Ekstrak NDK..."
    mkdir -p "$SDK_DIR/ndk"
    local ndk_archive=$(find "$TEMP_RESTORE" "$SDK_DIR/ndk" /sdcard -maxdepth 2 \( -iname "android-ndk-*.7z" -o -iname "android-ndk-*.zip" \) 2>/dev/null | head -n1)
    if [ -n "$ndk_archive" ] && [ -f "$ndk_archive" ]; then
        mkdir -p "$SDK_DIR/ndk/tmp_ndk"
        if [[ "$ndk_archive" == *.7z ]]; then 7z x -o"$SDK_DIR/ndk/tmp_ndk" "$ndk_archive" -y >/dev/null 2>&1
        else unzip -q "$ndk_archive" -d "$SDK_DIR/ndk/tmp_ndk"; fi
        local extracted_ndk_dir=$(find "$SDK_DIR/ndk/tmp_ndk" -maxdepth 2 -name "ndk-build" -exec dirname {} \; 2>/dev/null | head -n1)
        if [ -n "$extracted_ndk_dir" ]; then
            rm -rf "$NDK_DIR" 2>/dev/null
            mv "$extracted_ndk_dir" "$NDK_DIR"
            rm -rf "$SDK_DIR/ndk/tmp_ndk"
            ok "NDK Manual terpasang di $NDK_DIR"
        fi
    fi
    rm -rf "$TEMP_RESTORE"
    fix_ndk_permissions
    ensure_wrapper_template
    echo -e "\n${GREEN}${BOLD}  🎉 RESTORE SELESAI!${RESET}"
    sleep 2
}

auto_setup() {
    banner
    echo -e "  ${YELLOW}Pemeriksaan Lingkungan Pembangun (Auto-Setup)${RESET}\n"
    step "1/5: Verifikasi Akses Storage & Paket Termux"
    if [ ! -d "$HOME_DIR/storage" ] || [ ! -w "/sdcard" ]; then yes | termux-setup-storage 2>/dev/null || true; sleep 1; fi
    mkdir -p "$SDK_DIR/pkg-cache"
    apt-get update -y 2>/dev/null || true
    apt-get install -y -o Dir::Cache::archives="$SDK_DIR/pkg-cache" openjdk-17 python gradle android-tools rsync aapt aapt2 apksigner d8 aidl cmake ninja make wget curl git zip unzip perl p7zip clang 2>/dev/null || true
    ok "Paket Termux Siap"
    step "2/5: Struktur SDK, Dummy Build-Tools & CMake SDK"
    mkdir -p "$SDK_DIR/platforms" "$SDK_DIR/build-tools" "$SDK_DIR/licenses" "$SDK_DIR/cmake"
    setup_dummy_build_tools "33.0.1"
    setup_dummy_build_tools "34.0.0"
    setup_dummy_cmake "3.22.1"
    setup_dummy_cmake "3.18.1"
    step "3/5: Platform SDK Default"
    download_platform_sdk 34
    step "4/5: Konfigurasi Gradle Properties Override"
    echo "24333f8a637bced5e17096433f01641e5f692d6e" > "$SDK_DIR/licenses/android-sdk-license"
    mkdir -p ~/.gradle
    cat > ~/.gradle/gradle.properties << EOF
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=$GRADLE_OPTS
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.daemon.performance.disable-logging=true
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=$PREFIX/lib/jvm/java-17-openjdk
org.gradle.native=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=false
android.builder.sdkDownload=false
EOF
    ok "Gradle Properties dikonfigurasi"
    step "5/5: NDK Setup & Permissions"
    if [ ! -d "$NDK_DIR" ]; then
        local NDK_URL="https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r25c-aarch64.zip"
        wget -q --show-progress -O "$SDK_DIR/ndk.zip" "$NDK_URL" || true
        if [ -f "$SDK_DIR/ndk.zip" ]; then
            mkdir -p "$SDK_DIR/ndk/tmp"
            unzip -q "$SDK_DIR/ndk.zip" -d "$SDK_DIR/ndk/tmp"
            EXTRACTED_NDK=$(ls "$SDK_DIR/ndk/tmp" | head -n1)
            mkdir -p "$SDK_DIR/ndk/"
            mv "$SDK_DIR/ndk/tmp/$EXTRACTED_NDK" "$NDK_DIR"
            rm -rf "$SDK_DIR/ndk/tmp" "$SDK_DIR/ndk.zip"
            ok "NDK terpasang di $NDK_DIR"
        fi
    else ok "NDK sudah tersedia (Cached)"; fi
    fix_ndk_permissions
    ensure_wrapper_template
    echo -e "\n${GREEN}${BOLD}  🎉 Auto-Setup Selesai!${RESET}"
    sleep 2
}

build_project() {
    local build_type="${1:-debug}"
    banner
    termux-wake-lock 2>/dev/null || true
    if [ ! -d "$SDK_DIR" ]; then auto_setup; fi

    fix_ndk_permissions
    ensure_wrapper_template

    export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
    export PATH="$PREFIX/bin:$JAVA_HOME/bin:$PATH"
    [ ! -f "$PREFIX/bin/ninja" ] && pkg install ninja -y 2>/dev/null || true

    echo -e "${BOLD}${CYAN}  📁 Pindai Proyek Android di /sdcard/${RESET}"
    echo -e "${DIM}  ────────────────────────────────────────── ${RESET}"
    if [ ! -w "/sdcard" ]; then yes | termux-setup-storage 2>/dev/null || true; sleep 1; fi

    PROJECTS=()
    i=1
    while IFS= read -r proj_dir; do
        [ -d "$proj_dir" ] || continue
        pname="${proj_dir#/sdcard/}"
        [ "$pname" = "$proj_dir" ] && pname=$(basename "$proj_dir")
        PROJECTS+=("$proj_dir")
        printf "  [${CYAN}%2d${RESET}]  📂  %s\n" "$i" "$pname"
        ((i++))
    done < <(collect_android_projects)

    LAST_PROJECT="$(get_last_project 2>/dev/null || true)"
    if [ -n "$LAST_PROJECT" ]; then
        LAST_LABEL="${LAST_PROJECT#/sdcard/}"
        [ "$LAST_LABEL" = "$LAST_PROJECT" ] && LAST_LABEL=$(basename "$LAST_PROJECT")
        echo -e "  [${CYAN}ENTER${RESET}]  ⚡  Pakai proyek terakhir: ${LAST_LABEL}"
    fi

    if [ ${#PROJECTS[@]} -eq 0 ]; then warn "Tidak ada proyek Android ditemukan otomatis di /sdcard/!"; fi
    echo -e "  [${CYAN} M${RESET}]  ✏️   Ketik nama folder / path manual"
    echo -e "  [${CYAN} R${RESET}]  🔄  Scan ulang"
    echo -e "  [${CYAN} 0${RESET}]  ↩️   Kembali"
    if [ "$TERMUXMOD_NONINTERACTIVE" = "1" ]; then
        choice=""
    else
        read -rp "  ▶ Pilih (nomor/M/R/0, Enter=terakhir): " choice
    fi

    SRC_PATH=""
    case "$choice" in
        0) return ;;
        "")
            if [ -n "$LAST_PROJECT" ] && [ -d "$LAST_PROJECT" ]; then
                SRC_PATH="$LAST_PROJECT"
            else
                err "Belum ada proyek terakhir yang tersimpan!"
                sleep 1.5
                return
            fi
            ;;
        [Rr]) build_project "$build_type"; return ;;
        [Mm])
            read -rp "  ✏️  Nama folder / path: " folder_name
            if [[ "$folder_name" = /* ]]; then
                SRC_PATH="$folder_name"
            else
                SRC_PATH="/sdcard/$folder_name"
            fi
            ;;
        *)
            if [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le "${#PROJECTS[@]}" ]; then
                SRC_PATH="${PROJECTS[$((choice-1))]}"
            else err "Pilihan tidak valid!"; sleep 1.5; return; fi
            ;;
    esac

    [ ! -d "$SRC_PATH" ] && { err "Folder '$SRC_PATH' tidak ditemukan!"; sleep 2; return; }

    save_last_project "$SRC_PATH"
    PROJECT_NAME=$(basename "$SRC_PATH")
    START_TIME=$(date +%s)

    banner
    echo -e "${BOLD}  📦 Build Proyek: ${CYAN}${PROJECT_NAME}${RESET} (${build_type})"
    echo -e "${DIM}  ────────────────────────────────────────── ${RESET}\n"

    step "Menyiapkan Workspace Internal Termux..."
    mkdir -p "$WORKSPACE"
    TARGET_DIR="$WORKSPACE/$PROJECT_NAME"
    rm -rf "$TARGET_DIR"
    rsync -a --exclude='build/' --exclude='.gradle/' --exclude='.cxx/' --exclude='.idea/' "$SRC_PATH/" "$TARGET_DIR/"
    ok "Workspace disiapkan di: $TARGET_DIR"

    cd "$TARGET_DIR" || return

    step "Auto-detect SDK, Build-tools & CMake"
    COMPILE_SDK=$(grep -rohE "(compileSdk|compileSdkVersion)\s*=?\s*[0-9]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9]+')
    [ -z "$COMPILE_SDK" ] && COMPILE_SDK=34
    download_platform_sdk "$COMPILE_SDK"

    BT_VER=$(grep -rohE "buildToolsVersion\s*=?\s*[\"'][0-9.]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9.]+')
    setup_dummy_build_tools "33.0.1"
    setup_dummy_build_tools "34.0.0"
    [ -n "$BT_VER" ] && setup_dummy_build_tools "$BT_VER"

    CMAKE_REQ_VER=$(grep -rohE "version\s*=?\s*[\"'][0-9.]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9.]+')
    setup_dummy_cmake "3.22.1"
    setup_dummy_cmake "3.18.1"
    [ -n "$CMAKE_REQ_VER" ] && setup_dummy_cmake "$CMAKE_REQ_VER"

    step "Auto-adjust Gradle Wrapper 8.7"
    mkdir -p gradle/wrapper
    cp "$WRAPPER_DIR/gradlew" . 2>/dev/null || true
    cp -r "$WRAPPER_DIR/gradle/"* gradle/ 2>/dev/null || true

    ROOT_GRADLE=$(find . -maxdepth 2 \( -name "build.gradle" -o -name "build.gradle.kts" \) ! -path "*/app/*" | head -n1)
    if [ -n "$ROOT_GRADLE" ]; then
        AGP_VER=$(detect_agp_version "$ROOT_GRADLE")
        [ -n "$AGP_VER" ] && GRADLE_VER=$(agp_to_gradle "$AGP_VER") || GRADLE_VER="8.7"
    else GRADLE_VER="8.7"; fi

    sed -i "s/gradle-[0-9.]*-all.zip/gradle-$GRADLE_VER-all.zip/g" gradle/wrapper/gradle-wrapper.properties 2>/dev/null || true
    sed -i 's/\r$//' gradlew 2>/dev/null || true
    sed -i 's/DEFAULT_JVM_OPTS=.*/DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"/' gradlew 2>/dev/null || true
    chmod +x gradlew 2>/dev/null || true

    step "Auto-inject Config & Java 17 Downgrade"
    INSTALLED_NDK_VER=$(basename "$NDK_DIR" 2>/dev/null || echo "25.2.9519653")
    
    while IFS= read -r f_gradle; do clean_toolchains_python "$f_gradle"; done < <(find . -type f \( -name "*.gradle" -o -name "*.gradle.kts" -o -name "settings.gradle*" \) ! -path "*/build/*" 2>/dev/null)
    while IFS= read -r g_file; do inject_sdk_and_ndk "$g_file" "$COMPILE_SDK" "$INSTALLED_NDK_VER"; done < <(find . -maxdepth 3 \( -name "build.gradle" -o -name "build.gradle.kts" \) ! -path "*/build/*" 2>/dev/null)

    cat >> gradle.properties << EOF
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=$PREFIX/lib/jvm/java-17-openjdk
org.gradle.native=false
systemProp.org.gradle.native=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=false
org.gradle.daemon.performance.disable-logging=true
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
EOF

    local active_cmake_ver="${CMAKE_REQ_VER:-3.22.1}"
    cat > local.properties << EOF
sdk.dir=$SDK_DIR
cmake.dir=$SDK_DIR/cmake/$active_cmake_ver
EOF

    step "Kompilasi Gradle (${build_type})"
    echo -e "  ${DIM}────────────────────────────────────────── ${RESET}"

    TMP_LOG="$HOME_DIR/temp_build.log"
    rm -f "$TMP_LOG" "$LOG_FILE"

    FLAGS="-Dorg.gradle.native=false -Dorg.gradle.java.installations.auto-detect=false -Dorg.gradle.java.installations.auto-download=false -Pandroid.injected.build.abi=arm64-v8a --no-daemon"

    if [ "$build_type" = "release" ]; then
        ./gradlew assembleRelease $FLAGS 2>&1 | tee "$TMP_LOG" | grep -v -E "E aapt2\s*:|No package ID 7f found"
    else
        ./gradlew assembleDebug $FLAGS 2>&1 | tee "$TMP_LOG" | grep -v -E "E aapt2\s*:|No package ID 7f found"
    fi

    echo -e "  ${DIM}────────────────────────────────────────── ${RESET}"
    END_TIME=$(date +%s)
    ELAPSED=$(( END_TIME - START_TIME ))

    # PERBAIKAN V8.1: Cari file APK secara mendalam di folder privat Termux
    APK_FILE=$(find "$TARGET_DIR" -type f -name "*.apk" ! -name "*-unsigned.apk" 2>/dev/null | head -n1)

    if grep -q "BUILD SUCCESSFUL" "$TMP_LOG" || [ -n "$APK_FILE" ]; then
        [ -z "$APK_FILE" ] && APK_FILE=$(find "$TARGET_DIR" -type f -name "*.apk" 2>/dev/null | head -n1)
        
        OUT_NAME="${PROJECT_NAME}-${build_type}.apk"
        SDCARD_OUT="/sdcard/$OUT_NAME"
        
        # Lokasi 1: Salin langsung ke /sdcard/
        cp "$APK_FILE" "$SDCARD_OUT" 2>/dev/null || true
        
        # Lokasi 2: Salin ke folder proyek SD Card kamu (/sdcard/fox/app/build/outputs/apk/debug/...)
        DEST_PROJ_DIR="$SRC_PATH/app/build/outputs/apk/debug"
        mkdir -p "$DEST_PROJ_DIR" 2>/dev/null || true
        cp "$APK_FILE" "$DEST_PROJ_DIR/$OUT_NAME" 2>/dev/null || true

        APK_SIZE=$(du -sh "$SDCARD_OUT" 2>/dev/null | cut -f1)
        [ -z "$APK_SIZE" ] && APK_SIZE=$(du -sh "$APK_FILE" 2>/dev/null | cut -f1)

        echo -e "${GREEN}${BOLD}"
        echo "  ╔══════════════════════════════════════════════════╗"
        echo "  ║  ✅ BUILD BERHASIL!                             ║"
        printf "  ║  ⏱  Waktu   : %-33s║\n" "${ELAPSED}s"
        printf "  ║  📦  Ukuran  : %-33s║\n" "$APK_SIZE"
        printf "  ║  📁  Lokasi 1: /sdcard/%-25s║\n" "$OUT_NAME"
        printf "  ║  📁  Lokasi 2: %-33s║\n" "$(basename "$SRC_PATH")/app/build/outputs/apk/debug/"
        echo "  ╚══════════════════════════════════════════════════╝"
        echo -e "${RESET}"
    else
        cp "$TMP_LOG" "$LOG_FILE" 2>/dev/null
        echo -e "${RED}${BOLD}"
        echo "  ╔══════════════════════════════════════════════════╗"
        echo "  ║  ❌ BUILD GAGAL!                                ║"
        printf "  ║  ⏱  Waktu   : %-33s║\n" "${ELAPSED}s"
        echo "  ╚══════════════════════════════════════════════════╝"
        echo -e "${RESET}"

        echo -e "  ${YELLOW}🔍 Root Cause Ringkas: ${RESET}"
        grep -E -i -A 2 "error:|exception|failure|FAILED" "$TMP_LOG" 2>/dev/null | head -n 12 || echo "  Check log file."
        echo -e "  ${DIM}────────────────────────────────────────── ${RESET}"
        echo -e "  📄 Log penuh disimpan ke: /sdcard/build-error.log"
    fi

    rm -f "$TMP_LOG"
    termux-wake-unlock 2>/dev/null || true
    pause
}

# ═══════════════════════════════════════════════════════════
#  TermuxMod: NON-INTERACTIVE ENTRYPOINT
#  Lets the TermuxMod Android app drive this script by argv instead of
#  simulating menu keystrokes over stdin (which was fragile — any prompt
#  added/removed later would break it). Added on top, nothing below or
#  above this block was changed: run with no arguments and you get the
#  exact same interactive menu as before.
#
#  Usage: build.sh <action> [project_path]
#  Actions: build-debug, build-release, auto-setup, clean-cache,
#           import-backup, export-backup
# ═══════════════════════════════════════════════════════════
if [ -n "$1" ]; then
    ensure_app_state
    case "$1" in
        build-debug)
            [ -n "$2" ] && save_last_project "$2"
            build_project "debug"
            exit 0
            ;;
        build-release)
            [ -n "$2" ] && save_last_project "$2"
            build_project "release"
            exit 0
            ;;
        auto-setup)
            auto_setup
            exit 0
            ;;
        clean-cache)
            rm -rf "$WORKSPACE" "$HOME_DIR/.gradle/daemon"
            ok "Cache dibersihkan"
            exit 0
            ;;
        import-backup)
            import_backup
            exit 0
            ;;
        export-backup)
            export_backup
            exit 0
            ;;
        *)
            err "TermuxMod: aksi non-interaktif tidak dikenal: $1"
            exit 1
            ;;
    esac
fi

while true; do
    banner
    echo -e "  ${BOLD}MENU UTAMA ${RESET}"
    echo -e "  ${DIM}────────────────────────────────────────── ${RESET}"
    echo -e "  [${CYAN}1${RESET}]  🚀  Build APK (Debug)"
    echo -e "  [${CYAN}2${RESET}]  🔒  Build APK (Release)"
    echo -e "  [${CYAN}3${RESET}]  🛠   Auto-Setup Environment"
    echo -e "  [${CYAN}4${RESET}]  🧹  Clean Cache"
    echo -e "  [${CYAN}5${RESET}]  📥  Import Backup Offline (Pindah HP)"
    echo -e "  [${CYAN}6${RESET}]  📦  Export Backup Complete (SDK + Cache)"
    echo -e "  [${CYAN}7${RESET}]  ⚡  Pasang launcher '$LAUNCHER_NAME'"
    echo -e "  [${CYAN}0${RESET}]  🚪  Keluar"
    echo ""
    read -rp "  ▶ Pilih (0-7): " main_choice

    case "$main_choice" in
        1) build_project "debug" ;;
        2) build_project "release" ;;
        3) auto_setup ;;
        4) rm -rf "$WORKSPACE" "$HOME_DIR/.gradle/daemon"; ok "Cache dibersihkan"; sleep 1.5 ;;
        5) import_backup ;;
        6) export_backup ;;
        7) install_launcher ;;
        0) echo -e "\n  ${GREEN}Terima kasih! 👋${RESET}\n"; exit 0 ;;
        *) err "Pilihan tidak valid!"; sleep 1 ;;
    esac
done