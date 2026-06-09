FROM python:3.12-slim
WORKDIR /app
RUN pip install --no-cache-dir flask requests gunicorn google-auth
COPY app.py .
EXPOSE 8080
CMD ["gunicorn", "-b", "0.0.0.0:8080", "-w", "2", "--timeout", "30", "app:app"]
