# -*- coding: utf-8 -*-
"""
Gunicorn 生产环境配置
"""
import os
import multiprocessing

# ==================== 绑定地址 ====================
# 只监听内网，不暴露到公网（Java 通过内网调用）
bind = os.environ.get("GUNICORN_BIND", "0.0.0.0:9000")

# ==================== Worker 配置 ====================
# 使用 gthread worker（线程模式），适合 I/O 密集 + CPU 密集混合场景
# - AI 检测：CPU 密集（PyTorch 推理）
# - Humanizer：I/O 密集（调外部 API）
worker_class = "gthread"

# Worker 进程数：GPU 场景建议 1-2 个（多进程会重复加载模型占显存）
# CPU 场景可以适当增加，但模型会占内存，不宜太多
workers = int(os.environ.get("GUNICORN_WORKERS", 2))

# 每个 worker 的线程数
# AI 检测用 _model_lock 保证线程安全，所以多线程没问题
threads = int(os.environ.get("GUNICORN_THREADS", 4))

# ==================== 超时配置 ====================
# Humanizer 调外部 API 链路长（EN→JA→ZH→EN），需要较长超时
timeout = int(os.environ.get("GUNICORN_TIMEOUT", 300))  # 5 分钟

# 优雅关闭超时
graceful_timeout = 30

# Keep-alive 连接超时
keepalive = 5

# ==================== 预加载 ====================
# 预加载应用：所有 worker 共享同一份模型内存（通过 fork）
# 这样 2 个 worker 不会加载 2 份模型，节省内存/显存
preload_app = True

# ==================== 日志 ====================
accesslog = os.environ.get("GUNICORN_ACCESS_LOG", "-")  # "-" 表示输出到 stdout
errorlog = os.environ.get("GUNICORN_ERROR_LOG", "-")
loglevel = os.environ.get("GUNICORN_LOG_LEVEL", "info")

# ==================== 进程管理 ====================
# Worker 处理指定数量请求后自动重启，防止内存泄漏
max_requests = int(os.environ.get("GUNICORN_MAX_REQUESTS", 1000))
max_requests_jitter = 50  # 加随机偏移，避免所有 worker 同时重启
