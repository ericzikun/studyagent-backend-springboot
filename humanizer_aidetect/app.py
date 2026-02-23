# -*- coding: utf-8 -*-
"""
合并服务：AI Detection + Humanizer
端口 9000，路由：
  POST /predict         - 单块 AI 检测
  POST /predict_stream  - SSE 流式 AI 检测
  POST /process         - Humanizer 改写
  GET  /health          - 健康检查
"""
from flask import Flask, request, jsonify, Response
import torch
import torch.nn as nn
from transformers import AutoTokenizer, AutoConfig, AutoModel, PreTrainedModel
import os
import sys
import time
import uuid
import json
import re
import hashlib
import logging
from logging.handlers import RotatingFileHandler
import threading
import requests as http_requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry


# ==================== 模型定义 ====================
class DesklibAIDetectionModel(PreTrainedModel):
    config_class = AutoConfig
    def __init__(self, config):
        super().__init__(config)
        self.model = AutoModel.from_config(config)
        self.classifier = nn.Linear(config.hidden_size, 1)
        self.init_weights()

    def forward(self, input_ids, attention_mask=None, labels=None):
        outputs = self.model(input_ids, attention_mask=attention_mask)
        last_hidden_state = outputs[0]
        input_mask_expanded = attention_mask.unsqueeze(-1).expand(last_hidden_state.size()).float()
        sum_embeddings = torch.sum(last_hidden_state * input_mask_expanded, dim=1)
        sum_mask = torch.clamp(input_mask_expanded.sum(dim=1), min=1e-9)
        pooled_output = sum_embeddings / sum_mask
        logits = self.classifier(pooled_output)
        return {"logits": logits}


# ==================== Flask 初始化 ====================
app = Flask(__name__)

MAX_CONCURRENCY = int(os.environ.get("MAX_CONCURRENCY", 10))
_semaphore = threading.Semaphore(MAX_CONCURRENCY)
app.config['MAX_CONTENT_LENGTH'] = int(os.environ.get("MAX_CONTENT_MB", 2)) * 1024 * 1024


# ==================== 日志 ====================
def setup_logging():
    log_dir = os.environ.get("LOG_DIR", "logs")
    os.makedirs(log_dir, exist_ok=True)
    formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
    fh = RotatingFileHandler(os.path.join(log_dir, "app.log"), maxBytes=50*1024*1024, backupCount=5, encoding="utf-8")
    fh.setFormatter(formatter)
    ch = logging.StreamHandler(sys.stdout)
    ch.setFormatter(formatter)
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.addHandler(fh)
    root.addHandler(ch)

setup_logging()
logger = logging.getLogger(__name__)


@app.before_request
def attach_request_id():
    request.req_id = request.headers.get("X-Request-ID", uuid.uuid4().hex[:12])

def log_extra():
    rid = getattr(request, 'req_id', '-') if request else '-'
    return {"request_id": rid}


# ==================== AI Detection 模型加载 ====================
model = None
tokenizer = None
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
_model_lock = threading.Lock()

def load_model():
    global model, tokenizer
    model_path = os.environ.get("MODEL_PATH", "/app/model")
    logger.info(f"Loading model from: {model_path}, device: {device}")
    try:
        tokenizer = AutoTokenizer.from_pretrained(model_path)
        model = DesklibAIDetectionModel.from_pretrained(model_path)
        model.to(device)
        model.eval()
        logger.info("Model loaded successfully")
    except Exception as e:
        logger.error(f"Model load failed: {e}")

load_model()


# ==================== AI Detection 工具函数 ====================
def split_text_into_sentences(text):
    """按句子分割文本，每个句子单独作为一个检测单元"""
    # 按句号、问号、感叹号分割，保留分隔符
    parts = re.split(r'(?<=[.!?。！？])\s*', text)
    sentences = [s.strip() for s in parts if s.strip()]
    # 如果没有标点分割（比如只有一句话），直接返回整段
    if not sentences:
        return [text.strip()] if text.strip() else []
    return sentences

def predict_chunk(text):
    encoded = tokenizer(text, padding='max_length', truncation=True, max_length=512, return_tensors='pt')
    input_ids = encoded['input_ids'].to(device)
    attention_mask = encoded['attention_mask'].to(device)
    with _model_lock:
        with torch.no_grad():
            outputs = model(input_ids=input_ids, attention_mask=attention_mask)
            prob = torch.sigmoid(outputs["logits"]).item()
    return prob


# ==================== Humanizer 配置 ====================
SILICON_KEY = os.environ.get("SILICON_KEY")
SILICON_MODEL = os.environ.get("SILICON_MODEL", "tencent/Hunyuan-MT-7B")
YOUDAO_APP_KEY = os.environ.get("YOUDAO_APP_KEY")
YOUDAO_APP_SECRET = os.environ.get("YOUDAO_APP_SECRET")

def create_retry_session(retries=3, backoff=1.0, status_forcelist=(500, 502, 503, 504)):
    session = http_requests.Session()
    retry = Retry(total=retries, backoff_factor=backoff, status_forcelist=status_forcelist, allowed_methods=["POST", "GET"])
    adapter = HTTPAdapter(max_retries=retry)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session

http_session = create_retry_session()

def sf_api_call(prompt):
    if not SILICON_KEY:
        raise ValueError("环境变量 SILICON_KEY 未配置")
    url = "https://api.siliconflow.cn/v1/chat/completions"
    headers = {"Authorization": f"Bearer {SILICON_KEY}", "Content-Type": "application/json"}
    payload = {"model": SILICON_MODEL, "messages": [{"role": "user", "content": prompt}], "stream": False, "temperature": 0.2, "max_tokens": 2048}
    resp = http_session.post(url, json=payload, headers=headers, timeout=180)
    if resp.status_code != 200:
        raise Exception(f"SiliconFlow API Error: Status={resp.status_code}, Body={resp.text[:500]}")
    res = resp.json()
    if "choices" in res and len(res["choices"]) > 0:
        return res["choices"][0]["message"]["content"]
    raise Exception(f"SiliconFlow 响应格式异常: {res}")

def step1_trans(text):
    chunks, curr = [], ""
    for line in text.split('\n'):
        if len(curr) + len(line) < 1500:
            curr += line + "\n"
        else:
            if curr: chunks.append(curr)
            curr = line + "\n"
    if curr: chunks.append(curr)
    res_list = []
    for i, chunk in enumerate(chunks):
        if not chunk.strip():
            res_list.append("\n")
            continue
        logger.info(f"Humanize [{i+1}/{len(chunks)}]: EN->JA", extra=log_extra())
        ja = sf_api_call(f"把下面的文本翻译成日语，不要额外解释，保持原有Markdown格式：\n\n{chunk}")
        logger.info(f"Humanize [{i+1}/{len(chunks)}]: JA->ZH", extra=log_extra())
        zh = sf_api_call(f"把下面的文本翻译成中文，不要额外解释，保持原有Markdown格式：\n\n{ja}")
        res_list.append(zh)
    return "".join(res_list)

def yd_api_call(text):
    if not text or not text.strip(): return ""
    if not YOUDAO_APP_KEY or not YOUDAO_APP_SECRET:
        raise ValueError("环境变量 YOUDAO_APP_KEY 或 YOUDAO_APP_SECRET 未配置")
    q = text
    curtime = str(int(time.time()))
    salt = str(uuid.uuid1())
    sign_str = YOUDAO_APP_KEY + (q if len(q) <= 20 else q[0:10] + str(len(q)) + q[-10:]) + salt + curtime + YOUDAO_APP_SECRET
    sign = hashlib.sha256(sign_str.encode('utf-8')).hexdigest()
    data = {'from': 'zh-CHS', 'to': 'en', 'signType': 'v3', 'curtime': curtime, 'sign': sign, 'appKey': YOUDAO_APP_KEY, 'q': q, 'salt': salt}
    resp = http_session.post('https://openapi.youdao.com/api', data=data, timeout=15)
    res = resp.json()
    if res.get('errorCode') == '0':
        return "\n".join(res.get('translation', []))
    raise Exception(f"Youdao API Error: Code={res.get('errorCode')}")

def step2_trans(text):
    chunks = [text[i:i+4500] for i in range(0, len(text), 4500)]
    results = []
    for i, chunk in enumerate(chunks):
        logger.info(f"Humanize [{i+1}/{len(chunks)}]: ZH->EN", extra=log_extra())
        results.append(yd_api_call(chunk))
    return "".join(results)


# ==================== 路由: /health ====================
@app.route('/health', methods=['GET'])
def health_check():
    model_ok = model is not None and tokenizer is not None
    humanizer_ok = bool(SILICON_KEY) and bool(YOUDAO_APP_KEY) and bool(YOUDAO_APP_SECRET)
    return jsonify({
        "status": "ok" if (model_ok and humanizer_ok) else "degraded",
        "device": str(device),
        "model_loaded": model_ok,
        "humanizer_ready": humanizer_ok
    }), 200 if model_ok else 503


# ==================== 路由: /predict (单块，兼容旧接口) ====================
@app.route('/predict', methods=['POST'])
def predict():
    if not _semaphore.acquire(blocking=False):
        return jsonify({"code": 429, "msg": "Server busy"}), 429
    start_time = time.time()
    try:
        if model is None or tokenizer is None:
            return jsonify({"code": 503, "msg": "Model not loaded"}), 503
        data = request.get_json()
        if not data or not data.get('text', ''):
            return jsonify({"code": 400, "msg": "No text provided"}), 400
        text = data['text']
        prob = predict_chunk(text)
        label = "AI Generated" if prob >= 0.5 else "Human Written"
        elapsed = round(time.time() - start_time, 4)
        logger.info(f"Predict: {label} ({prob:.4f}) in {elapsed}s", extra=log_extra())
        return jsonify({"code": 200, "probability": prob, "label": label, "elapsed_seconds": elapsed})
    except Exception as e:
        logger.exception(f"Predict failed", extra=log_extra())
        return jsonify({"code": 500, "msg": str(e)}), 500
    finally:
        _semaphore.release()


# ==================== 路由: /predict_stream (SSE 流式) ====================
@app.route('/predict_stream', methods=['POST'])
def predict_stream():
    if not _semaphore.acquire(blocking=False):
        return jsonify({"code": 429, "msg": "Server busy"}), 429
    try:
        if model is None or tokenizer is None:
            _semaphore.release()
            return jsonify({"code": 503, "msg": "Model not loaded"}), 503
        data = request.get_json()
        if not data or not data.get('text', '').strip():
            _semaphore.release()
            return jsonify({"code": 400, "msg": "No text provided"}), 400
        text = data['text'].strip()
        logger.info(f"SSE predict_stream: {len(text)} chars", extra=log_extra())

        def generate():
            start_time = time.time()
            try:
                chunks = split_text_into_sentences(text)
                total = len(chunks)
                w_sum, w_total = 0.0, 0
                for i, chunk in enumerate(chunks):
                    try:
                        prob = predict_chunk(chunk)
                        label = "AI" if prob >= 0.5 else "Human"
                        w_sum += prob * len(chunk)
                        w_total += len(chunk)
                        evt = {"index": i+1, "total": total, "sentence": chunk[:80]+("..." if len(chunk)>80 else ""), "fullSentence": chunk, "probability": round(prob,4), "label": label, "weight": len(chunk)}
                    except Exception as e:
                        logger.error(f"Chunk {i+1} failed: {e}", extra=log_extra())
                        evt = {"index": i+1, "total": total, "sentence": chunk[:80]+("..." if len(chunk)>80 else ""), "fullSentence": chunk, "probability": 0, "label": "Error", "weight": len(chunk)}
                    yield f"event: chunk\ndata: {json.dumps(evt, ensure_ascii=False)}\n\n"

                final_prob = w_sum / w_total if w_total > 0 else 0
                elapsed = round(time.time() - start_time, 4)
                done = {"probability": round(final_prob,4), "label": "AI Generated" if final_prob>=0.5 else "Human Written", "totalChunks": total, "elapsed_seconds": elapsed}
                yield f"event: done\ndata: {json.dumps(done, ensure_ascii=False)}\n\n"
                logger.info(f"SSE done: {done['label']} ({final_prob:.4f}) in {elapsed}s", extra=log_extra())
            except Exception as e:
                logger.exception("SSE stream error", extra=log_extra())
                yield f"event: error\ndata: {json.dumps({'msg': str(e)}, ensure_ascii=False)}\n\n"
            finally:
                _semaphore.release()

        return Response(generate(), mimetype='text/event-stream', headers={
            'Cache-Control': 'no-cache', 'Connection': 'keep-alive',
            'X-Accel-Buffering': 'no', 'Access-Control-Allow-Origin': '*',
        })
    except Exception as e:
        _semaphore.release()
        return jsonify({"code": 500, "msg": str(e)}), 500


# ==================== 路由: /process (Humanizer) ====================
@app.route('/process', methods=['POST'])
def process_task():
    if not _semaphore.acquire(blocking=False):
        return jsonify({"code": 429, "msg": "Server busy"}), 429
    start_time = time.time()
    try:
        data = request.get_json()
        if not data:
            return jsonify({"code": 400, "msg": "No JSON data"}), 400
        input_text = data.get('text')
        file_url = data.get('file_url')
        if input_text:
            original_text = input_text
        elif file_url:
            dl = http_session.get(file_url, timeout=60)
            if dl.status_code != 200: raise Exception(f"Download failed: {dl.status_code}")
            original_text = dl.content.decode('utf-8')
        else:
            return jsonify({"code": 400, "msg": "Missing 'text' or 'file_url'"}), 400
        logger.info(f"Humanize: {len(original_text)} chars", extra=log_extra())
        zh_text = step1_trans(original_text)
        final_text = step2_trans(zh_text)
        elapsed = round(time.time() - start_time, 2)
        logger.info(f"Humanize done in {elapsed}s", extra=log_extra())
        return jsonify({"code": 200, "msg": "Success", "data": {"result": final_text}, "elapsed_seconds": elapsed})
    except Exception as e:
        logger.exception(f"Humanize failed", extra=log_extra())
        return jsonify({"code": 500, "msg": f"Process Failed: {str(e)}"}), 500
    finally:
        _semaphore.release()


# ==================== CORS ====================
@app.after_request
def add_cors(response):
    response.headers['Access-Control-Allow-Origin'] = '*'
    response.headers['Access-Control-Allow-Headers'] = 'Content-Type, X-Request-ID'
    response.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
    return response

@app.before_request
def handle_preflight():
    if request.method == 'OPTIONS':
        resp = app.make_default_options_response()
        resp.headers['Access-Control-Allow-Origin'] = '*'
        resp.headers['Access-Control-Allow-Headers'] = 'Content-Type, X-Request-ID'
        resp.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
        return resp

@app.errorhandler(413)
def too_large(e):
    return jsonify({"code": 413, "msg": "Request body too large"}), 413


if __name__ == '__main__':
    # 开发模式：直接运行 python app.py
    # 生产模式：用 gunicorn -c gunicorn.conf.py app:app
    app.run(host='0.0.0.0', port=9000, debug=True)
