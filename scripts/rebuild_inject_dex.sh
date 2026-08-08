#!/usr/bin/env bash
#
# 重建注入进游戏的 classes.dex 并更新 Bypass.zip。
# 用法：ANDROID_HOME 需指向已安装 build-tools 与 platforms 的 Android SDK。
# 由 GitHub Actions 在 assembleRelease 前调用。
#
set -euo pipefail

SDK="${ANDROID_HOME:-}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "ERROR: ANDROID_HOME 未设置或不存在: '$SDK'"
  exit 1
fi

BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLAT="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
if [ -z "$BT" ] || [ -z "$PLAT" ]; then
  echo "ERROR: 未找到 build-tools 或 platforms"
  exit 1
fi

ANDROID_JAR="$PLAT/android.jar"
D8="$BT/d8"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android/core/src/main/java/eternal/future"
SILKRIFT="$ROOT/android/core/libs/SilkRift.jar"
BYPASS_ZIP="$ROOT/composeApp/src/commonMain/resources/patch/Bypass.zip"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

mkdir -p "$OUT/classes"

echo ">> javac: 编译注入 dex 的 Java 源码"
javac -source 8 -target 8 -Xlint:-options \
  -cp "$ANDROID_JAR:$SILKRIFT" \
  -d "$OUT/classes" \
  "$SRC/TEFModLoader.java" \
  "$SRC/Inline.java" \
  "$SRC/Loader.java" \
  "$SRC/State.java" \
  "$SRC/utility/AssetManager.java" \
  "$SRC/utility/FileUtils.java"

echo ">> d8: 生成 classes.dex（含 silkrift 类）"
"$D8" --release --min-api 24 --lib "$ANDROID_JAR" \
  --output "$OUT" \
  "$OUT/classes" "$SILKRIFT"

echo ">> 更新 $BYPASS_ZIP 中的 classes.dex"
python3 - "$OUT/classes.dex" "$BYPASS_ZIP" <<'PY'
import sys, zipfile, shutil
newdex, src = sys.argv[1], sys.argv[2]
tmp = src + ".new"
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            zout.writestr(item, open(newdex, "rb").read())
        else:
            zout.writestr(item, zin.read(item.filename))
shutil.move(tmp, src)
print("Bypass.zip 已更新，classes.dex 大小: %d" % len(open(newdex, "rb").read()))
PY

echo ">> 注入 dex 重建完成"
