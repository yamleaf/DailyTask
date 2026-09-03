#!/usr/bin/env bash
# Build custom Shizuku (manager release APK + api jar) inside DT CI.
# Usage (only run when a shizuku package is provided):
#   SHIZUKU_PKG=com.exmple.custom.shizuku bash scripts/build_custom_shizuku.sh
#
# Steps:
#   1. clone https://github.com/yamleaf/Shizuku.git alpha branch
#   2. run rename_shizuku.sh (shipped in Shizuku repo) to produce the renamed copy
#   3. build custom manager release (signed with DT's shared KEY_STORE secrets;
#      falls back to debug signing when secrets are absent)
#   4. build the custom api jar from the renamed server AIDL
#   5. export CUSTOM_SHIZUKU_PKG / CUSTOM_SHIZUKU_LIB / custom manager APK path
#      via $GITHUB_ENV + $GITHUB_OUTPUT for the DT build step
set -euo pipefail

PKG="${SHIZUKU_PKG:-}"
if [[ -z "$PKG" ]]; then echo "SHIZUKU_PKG not set" >&2; exit 1; fi

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
SRC="$ROOT/shizuku-src"
CUSTOM="$ROOT/shizuku-custom"
ART="$ROOT/custom_shizuku_artifacts"
mkdir -p "$ART"

echo "==> [custom-shizuku] pkg=$PKG"

# ---- 1. clone alpha (含 submodule: api/ 指向 Shizuku-API，manifest.gradle.kts 等在其内) ----
# submodule 默认递归克隆完整历史较慢，加 --shallow-submodules 只取对应 commit 的浅克隆即可。
rm -rf "$SRC" "$CUSTOM"
git clone --depth 1 --recurse-submodules --shallow-submodules --branch alpha https://github.com/yamleaf/Shizuku.git "$SRC"

# ---- 2. rename ----
bash "$SRC/rename_shizuku.sh" "$SRC" "$CUSTOM" "$PKG"

# ---- 3. build custom manager release ----
(
  cd "$CUSTOM"
  chmod +x gradlew
  if [[ -n "${KEY_STORE:-}" ]]; then
    printf '%s' "$KEY_STORE" | base64 --decode > key.jks
    {
      echo "KEYSTORE_PASSWORD=${KEY_STORE_PASSWORD:-}"
      echo "KEYSTORE_ALIAS=${ALIAS:-}"
      echo "KEYSTORE_ALIAS_PASSWORD=${KEY_PASSWORD:-}"
      echo "KEYSTORE_FILE=../key.jks"
    } > signing.properties
    echo "==> [custom-shizuku] signing with shared KEY_STORE secret"
  else
    # signing.gradle falls back to debug signing when signing.properties is absent
    echo "==> [custom-shizuku] no KEY_STORE secret, manager release will be debug-signed"
  fi
  ./gradlew --no-daemon :manager:assembleRelease
)
MANAGER_APK="$(ls "$CUSTOM/manager/build/outputs/apk/release/"*release.apk 2>/dev/null | head -1 || true)"
if [[ -z "$MANAGER_APK" ]]; then echo "manager release APK not found" >&2; exit 1; fi

# 产物命名：shizuku-custom-<git短哈希>.apk（git 短哈希取 Shizuku 源码 HEAD，浅克隆即可稳定拿到；
# rename 副本不含 .git，故从 $SRC 读取）。直接产出 APK，不再预压 zip——
# GitHub artifact 下载会自动套一层 zip，预压缩会造成双重压缩。
GIT_SHORT="$(git -C "$SRC" rev-parse --short=8 HEAD)"
CUSTOM_APK_NAME="shizuku-custom-${GIT_SHORT}.apk"
cp "$MANAGER_APK" "$ART/$CUSTOM_APK_NAME"
echo "==> [custom-shizuku] manager apk -> $ART/$CUSTOM_APK_NAME"

# ---- 4. build the custom api jar from renamed server AIDL ----
AIDL_BT="$(ls -d "$ANDROID_HOME"/build-tools/*/aidl 2>/dev/null | sort -V | tail -1)"
ANDROID_JAR="$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
FRAMEWORK_AIDL="$(dirname "$ANDROID_JAR")/framework.aidl"
AIDL_DIR="$CUSTOM/api/aidl/src/main/aidl"
SLASH_PKG="${PKG//./\/}"
AIDL_FILES=( "$AIDL_DIR/$SLASH_PKG/server/"*.aidl )
if [[ ! -e "${AIDL_FILES[0]}" ]]; then echo "renamed server AIDL not found under $AIDL_DIR/$SLASH_PKG/server/" >&2; exit 1; fi

GEN="$ART/aigen"; CLS="$ART/aiclasses"
rm -rf "$GEN" "$CLS"; mkdir -p "$GEN" "$CLS"
for f in "${AIDL_FILES[@]}"; do
  "$AIDL_BT" --lang=java -p "$FRAMEWORK_AIDL" -I "$AIDL_DIR" -o "$GEN" "$f"
done
find "$GEN" -name '*.java' > "$ART/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$CLS" @"$ART/sources.txt"
"$JAVA_HOME/bin/jar" cf "$ART/shizuku-custom-api.jar" -C "$CLS" .
echo "==> [custom-shizuku] api jar -> $ART/shizuku-custom-api.jar"

# ---- 5. export for DT build step ----
{
  echo "CUSTOM_SHIZUKU_PKG=$PKG"
  echo "CUSTOM_SHIZUKU_LIB=$ART/shizuku-custom-api.jar"
  echo "CUSTOM_SHIZUKU_MANAGER=$ART/$CUSTOM_APK_NAME"
  echo "CUSTOM_SHIZUKU_ART_NAME=${CUSTOM_APK_NAME%.apk}"
} >> "$GITHUB_ENV"
echo "==> [custom-shizuku] done"