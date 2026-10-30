#!/bin/zsh
# 把已构建好的版本发布到 Gitee 公开仓库 Air-0000/posealarm-update (App 的国内首选更新源)：
#   仓库不存在时创建 → 新建发行版并上传安装包 → 最后更新 version.json
# 先传安装包再改 version.json，避免手机看到新版本时附件还没传完。
#
# 用法：GITEE_TOKEN=<Gitee 私人令牌> scripts/publish_gitee.sh v1.0.1
#   令牌在 https://gitee.com/profile/personal_access_tokens 创建，勾选 projects 权限
#   需要 dist/PoseAlarm_<标签>.apk 与 dist/version.json (由 scripts/release.sh 生成)
set -euo pipefail

cd "$(dirname "$0")/.."

TAG=${1:?用法: scripts/publish_gitee.sh <标签，如 v1.0.1>}
: "${GITEE_TOKEN:?请先设置 GITEE_TOKEN}"
OWNER=Air-0000
REPO=posealarm-update
API=https://gitee.com/api/v5
APK=dist/PoseAlarm_$TAG.apk
[[ -f $APK && -f dist/version.json ]] || { echo "缺少 $APK 或 dist/version.json，请先运行 scripts/release.sh"; exit 1; }

# 令牌放在 curl 的配置里经标准输入传入，不出现在进程列表中
api() {
  local method=$1 path=$2; shift 2
  print -r -- "form = \"access_token=$GITEE_TOKEN\"" | curl -sS -f -X "$method" -K - "$API$path" "$@"
}

echo "==> 检查 Gitee 仓库 $OWNER/$REPO"
if ! curl -sS -f -o /dev/null "$API/repos/$OWNER/$REPO"; then
  echo "    仓库不存在，创建公开仓库"
  api POST /user/repos -F name=$REPO -F private=false -F auto_init=true \
    -F "description=起床动起来 (PoseAlarm) 安装包与应用内更新文件" >/dev/null
  sleep 2
fi

echo "==> 新建发行版 $TAG"
NOTES=$(python3 -c "import json;print(json.load(open('dist/version.json',encoding='utf-8'))['updateLog'])")
RELEASE_ID=$(api POST /repos/$OWNER/$REPO/releases -F tag_name=$TAG -F name="PoseAlarm $TAG" \
  -F body="$NOTES" -F target_commitish=master -F prerelease=false | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")

echo "==> 上传安装包 (约 $(du -h $APK | cut -f1))"
api POST /repos/$OWNER/$REPO/releases/$RELEASE_ID/attach_files -F "file=@$APK" >/dev/null

echo "==> 更新 version.json"
CONTENT=$(base64 < dist/version.json | tr -d '\n')
SHA=$(curl -sS "$API/repos/$OWNER/$REPO/contents/version.json" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(d.get('sha','') if isinstance(d,dict) else '')")
if [[ -n $SHA ]]; then
  api PUT /repos/$OWNER/$REPO/contents/version.json -F content="$CONTENT" -F sha=$SHA -F message="release $TAG" >/dev/null
else
  api POST /repos/$OWNER/$REPO/contents/version.json -F content="$CONTENT" -F message="release $TAG" >/dev/null
fi

echo "==> 完成"
echo "    发行版：https://gitee.com/$OWNER/$REPO/releases/tag/$TAG"
echo "    更新文件：https://gitee.com/$OWNER/$REPO/raw/master/version.json"
