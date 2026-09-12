FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PHONE_FOR_AI_STATE_DIR=/data

WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
COPY web ./web
RUN pip install --no-cache-dir .

VOLUME ["/data"]
EXPOSE 8765
ENTRYPOINT ["phone-for-ai"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8765"]
