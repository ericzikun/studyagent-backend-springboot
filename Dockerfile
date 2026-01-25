# SpringBoot 后端 Dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build

# 设置工作目录
WORKDIR /app

# 复制 pom.xml 文件（利用 Docker 缓存）
COPY pom.xml .
COPY agent-api/pom.xml agent-api/
COPY agent-service/pom.xml agent-service/
COPY agent-infra/pom.xml agent-infra/
COPY agent-start/pom.xml agent-start/

# 下载依赖（利用 Docker 缓存）
RUN mvn dependency:go-offline -B

# 复制源代码
COPY . .

# 构建项目
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:17-jre-alpine

# 安装 wget 用于健康检查
RUN apk add --no-cache wget

# 设置工作目录
WORKDIR /app

# 创建必要的目录
RUN mkdir -p /app/storage/uploads /app/storage/outputs /app/logs

# 从构建阶段复制 JAR 文件
# 注意：Spring Boot Maven Plugin 会生成可执行的 JAR 文件
COPY --from=build /app/agent-start/target/agent-start-*.jar app.jar

# 设置环境变量
ENV SPRING_PROFILES_ACTIVE=docker
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# 启动应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

