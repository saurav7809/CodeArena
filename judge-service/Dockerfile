FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends g++ openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /judge
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app

USER 10001:10001
EXPOSE 8091
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091", "--no-proxy-headers"]
