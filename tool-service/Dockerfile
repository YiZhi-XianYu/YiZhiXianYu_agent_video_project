# ── Python Tool Service ──
# CPU-only image; swap base to nvidia/cuda:12.4-runtime-ubuntu22.04 for GPU
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install PyTorch CPU-only first (smaller image)
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download faster-whisper small model (optional, comment out for faster build)
# RUN python -c "from faster_whisper import WhisperModel; WhisperModel('small', device='cpu', compute_type='int8')"

COPY . .

RUN mkdir -p runtime/artifacts runtime/bgm runtime/executions

EXPOSE 8090

# HuggingFace cache for CLIP / Whisper models
ENV HF_HOME=/app/.cache/huggingface
VOLUME ["/app/.cache/huggingface", "/app/runtime"]

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
