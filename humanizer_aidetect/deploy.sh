#!/bin/bash
set -e

CONTAINER_NAME="ai-detect"
ENV_FILE="/app/backend_aidetect_humanizer/.env"
LOCAL_DIR="/app/backend_aidetect_humanizer"

# 自动获取当前运行容器的镜像作为基础，找不到就用 latest
CURRENT_IMAGE=$(docker inspect --format='{{.Config.Image}}' $CONTAINER_NAME 2>/dev/null || echo "crpi-r4tdtw0d8j47g8a4.cn-hongkong.personal.cr.aliyuncs.com/my-fc-demo-jyx/aidetect:v2")
echo "当前镜像: $CURRENT_IMAGE"

echo "=== 1. 停止并删除旧容器 ==="
docker stop $CONTAINER_NAME 2>/dev/null && docker rm $CONTAINER_NAME 2>/dev/null || true

echo "=== 2. 启动临时容器 ==="
docker run -d --name $CONTAINER_NAME --restart always -p 9000:9000 --env-file $ENV_FILE $CURRENT_IMAGE bash -c "sleep infinity"

echo "=== 3. 复制最新文件到容器 ==="
docker cp ${LOCAL_DIR}/app.py $CONTAINER_NAME:/app/app.py
docker cp ${LOCAL_DIR}/gunicorn.conf.py $CONTAINER_NAME:/app/gunicorn.conf.py
docker cp ${LOCAL_DIR}/requirements.txt $CONTAINER_NAME:/app/requirements.txt

echo "=== 4. 安装/更新依赖 ==="
docker exec $CONTAINER_NAME pip install --no-cache-dir -r /app/requirements.txt
echo "=== 4.1 下载 nltk punkt 数据 ==="
docker exec $CONTAINER_NAME python -c "import nltk; nltk.download('punkt_tab', quiet=True)"

echo "=== 5. 提交为新镜像 (latest) ==="
docker commit $CONTAINER_NAME aidetect:latest

echo "=== 6. 用 latest 镜像正式启动 ==="
docker stop $CONTAINER_NAME && docker rm $CONTAINER_NAME
docker run -d --name $CONTAINER_NAME --restart always -p 9000:9000 --env-file $ENV_FILE aidetect:latest gunicorn -c /app/gunicorn.conf.py app:app

echo "=== 7. 等待启动 ==="
sleep 8

echo "=== 8. 检查状态 ==="
docker logs $CONTAINER_NAME --tail 20
echo ""
curl -s http://localhost:9000/health | python3 -m json.tool || echo "Health check failed"
echo ""
echo "=== 部署完成 ==="
