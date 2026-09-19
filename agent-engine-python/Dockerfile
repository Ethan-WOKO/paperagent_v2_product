FROM ghcr.io/astral-sh/uv:0.8.22 AS uv
FROM python:3.12-slim-bookworm
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app/agent-engine-python
ENV UV_PYTHON_DOWNLOADS=never \
    UV_LINK_MODE=copy \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/app/agent-engine-python/.venv/bin:$PATH" \
    PAPERAGENT_PYTHON_HOST=0.0.0.0 \
    PAPERAGENT_PYTHON_DATA_DIR=/var/lib/paperagent-python
COPY pyproject.toml uv.lock ./
COPY src ./src
RUN uv sync --locked --no-dev --no-editable \
    && useradd --uid 10001 --create-home engine \
    && mkdir -p /var/lib/paperagent-python \
    && chown engine:engine /var/lib/paperagent-python
USER engine
EXPOSE 8097
ENTRYPOINT ["paperagent-python", "serve-product"]
