FROM python:3.12-slim
WORKDIR /app
# KHANDAQ (audit A8): pin direct dependencies to exact versions (was unpinned → supply-chain drift).
# maxminddb + the DB-IP geoip database were dropped together with the /stats dashboard (attack-surface
# reduction) — the relay no longer resolves or stores countries.
RUN pip install --no-cache-dir \
    flask==3.0.3 requests==2.32.3 gunicorn==23.0.0 google-auth==2.35.0
COPY app.py .
EXPOSE 8080
CMD ["gunicorn", "-b", "0.0.0.0:8080", "-w", "2", "--timeout", "30", "app:app"]
