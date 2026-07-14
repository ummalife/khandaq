FROM python:3.12-slim
WORKDIR /app
RUN pip install --no-cache-dir flask requests gunicorn google-auth maxminddb
# KHANDAQ stats: free DB-IP country-lite database (CC-BY 4.0, no license key needed) for
# aggregate-only country stats on /stats. IPs themselves are never stored.
ADD https://download.db-ip.com/free/dbip-country-lite-2026-07.mmdb.gz /app/dbip-country.mmdb.gz
RUN gunzip -f /app/dbip-country.mmdb.gz
COPY app.py .
EXPOSE 8080
CMD ["gunicorn", "-b", "0.0.0.0:8080", "-w", "2", "--timeout", "30", "app:app"]
