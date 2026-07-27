# ── Python Tool Service ──
# CPU-only image; swap base to nvidia/cuda:12.4-runtime-ubuntu22.04 for GPU
FROM python:3.12-slim

ARG DEBIAN_MIRROR=http://deb.debian.org/debian
ARG PYPI_INDEX_URL=https://pypi.org/simple

RUN sed -i "s|http://deb.debian.org/debian|${DEBIAN_MIRROR}|g" \
        /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=30 \
        -o Acquire::https::Timeout=30 update \
    && apt-get install -y --no-install-recommends \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install PyTorch CPU-only first (smaller image)
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

COPY tool-service/requirements.txt .
RUN pip install --no-cache-dir --retries 5 --timeout 120 \
    --index-url "${PYPI_INDEX_URL}" -r requirements.txt

# Pre-download faster-whisper small model (optional, comment out for faster build)
# RUN python -c "from faster_whisper import WhisperModel; WhisperModel('small', device='cpu', compute_type='int8')"

COPY tool-service/app/ ./app/
COPY contracts/llm/ /contracts/llm/

RUN mkdir -p runtime/artifacts runtime/bgm runtime/executions

EXPOSE 8090

# HuggingFace cache for CLIP / Whisper models
ENV HF_HOME=/app/.cache/huggingface
VOLUME ["/app/.cache/huggingface", "/app/runtime"]

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
