FROM python:3.12-slim
WORKDIR /app
# KHANDAQ (audit A8): pin direct dependencies to exact versions (was unpinned → supply-chain drift).
RUN pip install --no-cache-dir \
    flask==3.0.3 requests==2.32.3 gunicorn==23.0.0 google-auth==2.35.0 maxminddb==2.6.2
# KHANDAQ stats: free DB-IP country-lite database (CC-BY 4.0, no license key) for aggregate-only
# country stats on /stats. IPs themselves are never stored.
# KHANDAQ (audit A8): integrity-verified download (SHA-256) instead of a bare ADD (no integrity check).
ARG DBIP_URL=https://download.db-ip.com/free/dbip-country-lite-2026-07.mmdb.gz
ARG DBIP_SHA256=989c57a9ad1c1c93032e28acc643afdf03597ea28480520f6f1c76ea6420507f
RUN python -c "import urllib.request,hashlib,sys; \
d=urllib.request.urlopen('$DBIP_URL').read(); \
h=hashlib.sha256(d).hexdigest(); \
sys.exit('dbip sha256 mismatch: '+h) if h!='$DBIP_SHA256' else open('/app/dbip-country.mmdb.gz','wb').write(d)"
RUN gunzip -f /app/dbip-country.mmdb.gz
COPY app.py .
EXPOSE 8080
CMD ["gunicorn", "-b", "0.0.0.0:8080", "-w", "2", "--timeout", "30", "app:app"]
