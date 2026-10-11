FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY assistant ./assistant
EXPOSE 8001
CMD ["uvicorn", "assistant.app:app", "--host", "0.0.0.0", "--port", "8001"]
