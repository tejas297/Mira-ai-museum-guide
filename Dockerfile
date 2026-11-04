FROM python:3.12-slim

WORKDIR /app

COPY server/requirements.txt server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt

COPY server/ server/
COPY demo/ demo/

# Database lives on the Fly volume, not in the image.
ENV MIRA_DB_PATH=/data/mira.db

WORKDIR /app/server
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
