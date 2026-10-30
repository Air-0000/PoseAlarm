#!/bin/zsh
# 把已构建好的版本发布到 Gitee 公开仓库 Air-0000/posealarm-update (App 的国内首选更新源)：
#   仓库不存在时创建并设为公开 → 新建发行版并上传安装包 → 最后更新 version.json
# 先传安装包再改 version.json，避免手机看到新版本时附件还没传完。
#
# 用法：scripts/publish_gitee.sh v1.0.1
#   令牌优先取环境变量 GITEE_TOKEN，否则从 macOS 钥匙串读取 (服务名 gitee-token，与 CheckMarkScanner 共用)
#   需要 dist/PoseAlarm_<标签>.apk 与 dist/version.json (由 scripts/release.sh 生成)
set -euo pipefail

cd "$(dirname "$0")/.."

TAG=${1:?用法: scripts/publish_gitee.sh <标签，如 v1.0.1>}
TOKEN=${GITEE_TOKEN:-$(security find-generic-password -s gitee-token -w 2>/dev/null || true)}
[[ -n $TOKEN ]] || { echo "没有 Gitee 令牌：设置 GITEE_TOKEN，或存入钥匙串 (security add-generic-password -s gitee-token -a Air-0000 -w)"; exit 1; }
OWNER=Air-0000
REPO=posealarm-update
API=https://gitee.com/api/v5
APK=dist/PoseAlarm_$TAG.apk
[[ -f $APK && -f dist/version.json ]] || { echo "缺少 $APK 或 dist/version.json，请先运行 scripts/release.sh"; exit 1; }

# 令牌放在 curl 的配置里经标准输入传入，不出现在命令行和进程列表中
api() {
  local method=$1 endpoint=$2; shift 2
  print -r -- "header = \"Authorization: token $TOKEN\"" | curl -sS -f -X "$method" -K - "$API$endpoint" "$@"
}

echo "==> 检查 Gitee 仓库 $OWNER/$REPO"
if ! api GET /repos/$OWNER/$REPO -o /dev/null; then
  echo "    仓库不存在，创建"
  api POST /user/repos -F name=$REPO -F auto_init=true \
    -F "description=起床动起来 (PoseAlarm) 安装包与应用内更新文件" >/dev/null
  sleep 2
fi
# Gitee 新建仓库时可能被强制设为私有，显式改为公开 (App 匿名读取 version.json 需要)
api PATCH /repos/$OWNER/$REPO -F name=$REPO -F private=false >/dev/null

echo "==> 新建发行版 $TAG"
NOTES=$(python3 -c "import json;print(json.load(open('dist/version.json',encoding='utf-8'))['updateLog'])")
RELEASE_ID=$(api GET /repos/$OWNER/$REPO/releases/tags/$TAG 2>/dev/null | python3 -c "
import json,sys
try: print(json.load(sys.stdin).get('id') or '')
except Exception: print('')")
if [[ -z $RELEASE_ID ]]; then
  RELEASE_ID=$(api POST /repos/$OWNER/$REPO/releases -F tag_name=$TAG -F name="PoseAlarm $TAG" \
    -F body="$NOTES" -F target_commitish=master -F prerelease=false | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
fi
echo "    发行版 id $RELEASE_ID"

APK_NAME=${APK:t}
if api GET /repos/$OWNER/$REPO/releases/$RELEASE_ID/attach_files | grep -q "\"name\":\"$APK_NAME\""; then
  echo "==> 安装包已在发行版附件里，跳过上传"
else
  echo "==> 上传安装包 (约 $(du -h $APK | cut -f1)，可能需要几分钟)"
  # 去掉 Expect: 100-continue，否则经代理上传大文件会一直卡住没有响应
  api POST /repos/$OWNER/$REPO/releases/$RELEASE_ID/attach_files -H 'Expect:' -F "file=@$APK" >/dev/null
fi

echo "==> 更新 version.json"
CONTENT=$(base64 < dist/version.json | tr -d '\n')
SHA=$(api GET /repos/$OWNER/$REPO/contents/version.json 2>/dev/null | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(d.get('sha','') if isinstance(d,dict) else '')
except Exception: print('')")
if [[ -n $SHA ]]; then
  api PUT /repos/$OWNER/$REPO/contents/version.json -F content="$CONTENT" -F sha=$SHA -F message="release $TAG" >/dev/null
else
  api POST /repos/$OWNER/$REPO/contents/version.json -F content="$CONTENT" -F message="release $TAG" >/dev/null
fi

echo "==> 完成"
echo "    发行版：https://gitee.com/$OWNER/$REPO/releases/tag/$TAG"
echo "    更新文件：https://gitee.com/$OWNER/$REPO/raw/master/version.json"
