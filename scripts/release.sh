#!/bin/zsh
# 发布新版本：编译测试 → 生成 version.json → 提交并打标签 → GitHub 发行版 → Gitee 发行版
#
# 用法：
#   1. 先改好 app/build.gradle 里的 versionCode / versionName，合并到 main
#   2. 把更新说明写进一个文本文件 (每行一条，App 内更新弹窗直接显示)
#   3. scripts/release.sh 更新说明.txt
#      Gitee 令牌从钥匙串 gitee-token 读取 (与 CheckMarkScanner 共用)，也可用环境变量 GITEE_TOKEN 指定
#
# 下载线路：
#   - Gitee 公开仓库 Air-0000/posealarm-update：version.json + 发行版附件 (国内快，App 优先检测)
#   - GitHub Air-0000/PoseAlarm 发行版：安装包 + version.json 附件 (备用)
set -euo pipefail

cd "$(dirname "$0")/.."

NOTES_FILE=${1:?用法: scripts/release.sh <更新说明文件>}
[[ -f $NOTES_FILE ]] || { echo "找不到更新说明文件: $NOTES_FILE"; exit 1; }

GITHUB_REPO=Air-0000/PoseAlarm
GITEE_OWNER=Air-0000
GITEE_REPO=posealarm-update
GITEE_API=https://gitee.com/api/v5

VERSION_CODE=$(sed -n 's/^ *versionCode \([0-9]*\).*/\1/p' app/build.gradle)
VERSION_NAME=$(sed -n 's/^ *versionName "\(.*\)".*/\1/p' app/build.gradle)
TAG=v$VERSION_NAME
APK_NAME=PoseAlarm_$TAG.apk

[[ $(git branch --show-current) == main ]] || { echo "请在 main 分支上发布"; exit 1; }
[[ -z $(git status --porcelain) ]] || { echo "工作区有未提交的改动，请先提交"; exit 1; }
git fetch -q origin main
[[ $(git rev-parse HEAD) == $(git rev-parse origin/main) ]] || { echo "本地 main 与 origin/main 不一致，请先同步"; exit 1; }
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then echo "标签 $TAG 已存在，请先改版本号"; exit 1; fi

echo "==> 编译并测试 $TAG (versionCode $VERSION_CODE)"
./gradlew --console=plain -q clean testDebugUnitTest assembleDebug

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk "dist/$APK_NAME"
SHA256=$(shasum -a 256 "dist/$APK_NAME" | cut -d' ' -f1)
SIZE_MB=$(python3 -c "import os;print('%.1fMB' % (os.path.getsize('dist/$APK_NAME')/1048576))")

echo "==> 生成 version.json"
python3 - "$NOTES_FILE" <<EOF
import json, sys
notes = open(sys.argv[1], encoding="utf-8").read().strip()
data = {
    "versionCode": $VERSION_CODE,
    "versionName": "$VERSION_NAME",
    "apkUrl": "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/releases/download/$TAG/$APK_NAME",
    "mirrorUrls": ["https://github.com/$GITHUB_REPO/releases/download/$TAG/$APK_NAME"],
    "apkSize": "$SIZE_MB",
    "updateLog": notes,
    "forceUpdate": False,
    "sha256": "$SHA256",
}
with open("version.json", "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
EOF
cp version.json dist/version.json

echo "==> 提交 version.json 并打标签 $TAG"
git add version.json
git commit -q -m "chore: release $TAG"
git tag -a "$TAG" -m "PoseAlarm $TAG"
git push -q origin main "$TAG"

echo "==> 发布到 GitHub"
gh release create "$TAG" "dist/$APK_NAME" dist/version.json \
  --repo "$GITHUB_REPO" --verify-tag \
  --title "PoseAlarm $TAG" --notes-file "$NOTES_FILE"

echo "==> 发布到 Gitee"
if ! scripts/publish_gitee.sh "$TAG"; then
  echo "Gitee 发布失败 (GitHub 已发布成功)。排查后单独重试：scripts/publish_gitee.sh $TAG"
  exit 1
fi
