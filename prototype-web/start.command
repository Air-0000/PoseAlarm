#!/bin/zsh
# 双击运行：启动本地服务并在浏览器打开闹钟
cd "$(dirname "$0")"
(sleep 1 && open http://localhost:8765) &
python3 -m http.server 8765
