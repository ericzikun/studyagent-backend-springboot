#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME_17="/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"
JAVA="$JAVA_HOME_17/bin/java"
MVN="mvn"
PORT="${PORT:-8080}"

cd "$SCRIPT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

if [ ! -f "$JAVA" ]; then
    error "未找到 JDK 17: $JAVA"
    error "请确认已安装 Zulu JDK 17"
    exit 1
fi

export JAVA_HOME="$JAVA_HOME_17"

EXISTING_PID=$(lsof -ti "tcp:${PORT}" 2>/dev/null || true)
if [ -n "$EXISTING_PID" ]; then
    warn "端口 ${PORT} 已被占用 (PID: $EXISTING_PID)"
    read -p "是否停止旧进程并重新启动? [y/N] " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        for pid in $EXISTING_PID; do
            kill "$pid" && info "已停止旧进程 $pid" || true
        done
        sleep 1
    else
        info "退出，保持旧进程运行"
        exit 0
    fi
fi

info "开始构建项目 (跳过测试)..."
$MVN clean install -DskipTests -q
info "构建完成"

info "启动 Mock 后端 (端口 ${PORT})..."
info "日志输出到: $SCRIPT_DIR/logs/mock.log"
info "按 Ctrl+C 停止服务"
echo ""

mkdir -p "$SCRIPT_DIR/logs"

CLASSPATH="$SCRIPT_DIR/agent-start/target/classes"
CLASSPATH="$CLASSPATH:$(cd "$SCRIPT_DIR/agent-start" && $MVN dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout 2>/dev/null)"

exec "$JAVA" \
    -cp "$CLASSPATH" \
    com.studyagent.start.MockStudyAgentApplication \
    --server.port="$PORT" \
    2>&1 | tee "$SCRIPT_DIR/logs/mock.log"
