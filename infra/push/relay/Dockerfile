# KHANDAQ (audit 2026-08-21, K-05) — the relay image, pinned by digest and hash-locked.
#
# `FROM python:3.12-slim` is a MOVING tag. Whatever Docker Hub pointed it at on the day of a rebuild
# went into production with no diff to review, and the same Dockerfile produced different images over
# time by design. The digest below is what that tag resolved to on 2026-08-21; a tag reference is
# kept alongside it purely so a human can read which release it is.
#
# Re-derive it:
#   TOKEN=$(curl -s "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/python:pull" \
#           | sed -E 's/.*"token":"([^"]+)".*/\1/')
#   curl -sI -H "Authorization: Bearer $TOKEN" \
#        -H 'Accept: application/vnd.oci.image.index.v1+json' \
#        https://registry-1.docker.io/v2/library/python/manifests/3.12-slim \
#     | tr -d '\r' | grep -i docker-content-digest
#
# This is the multi-arch index (linux/amd64 child
# sha256:876416ecde9aca2bcc90e1fb0c7a9500bbf749f5788b70f82d4c5a5c2357f8b4), so it still builds on
# arm64 hosts. Bumping it is a reviewed commit, which is the entire point.
FROM python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a

# KHANDAQ (K-05): the container ran as root. Nothing here needs it — the process binds 8080, not a
# privileged port, and writes exactly one directory. A fixed high UID rather than a name, so the
# ownership of the /data volume is predictable from outside the container.
ARG RELAY_UID=10001
ARG RELAY_GID=10001

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# KHANDAQ (K-05): the whole dependency closure, every artifact hash-checked.
#
# This used to be `pip install flask==... requests==... gunicorn==... google-auth==...`: four DIRECT
# versions pinned, every transitive dependency free to be whatever PyPI served that minute, and not
# one byte verified. --require-hashes turns a substituted package into a build failure; and since
# every hash must be present for every requirement, it also makes it impossible to add a dependency
# without regenerating the lock.
#
# --only-binary=:all: is the second half: no sdist, so no setup.py executes during the build. The
# final rootfs carries no compiler anyway (the base image purges gcc/libc6-dev inside the layer that
# builds CPython), so this is a guarantee rather than a preference.
#
# Two of the previously pinned versions had known advisories, only one of which the audit named:
#   requests 2.32.3 — CVE-2024-47081 (netrc credential leak, fixed 2.32.4) AND
#                     CVE-2026-25645 (insecure temp-file reuse, fixed 2.33.0), so the audit's
#                     suggested 2.32.4 would still have been vulnerable.
#   flask    3.0.3  — CVE-2026-27205 (missing `Vary: Cookie`, fixed 3.1.3), not mentioned at all.
# Both clear at the locked versions; scripts/check-python-deps.py re-checks the whole closure in CI.
COPY requirements.txt ./
RUN pip install --require-hashes --only-binary=:all: -r requirements.txt \
 && rm -rf /root/.cache

COPY app.py ./

# /data holds stats.db (wake coalescing, single-use signatures, the per-IP rate window and the
# auth-adoption counters). Created and owned here so that the named volume docker-compose mounts over
# it inherits this ownership on first use — otherwise a fresh volume is root-owned and a non-root
# relay cannot write its own state.
RUN mkdir -p /data \
 && chown -R ${RELAY_UID}:${RELAY_GID} /data /app

USER ${RELAY_UID}:${RELAY_GID}

EXPOSE 8080

# The relay already answers /health; ask it, rather than assuming a running process is a working one.
# Uses the interpreter that is definitely present — the slim image has no curl or wget.
HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=4).status == 200 else 1)"]

# --worker-tmp-dir is set to the tmpfs compose mounts: gunicorn's worker heartbeat file lives there,
# and with a read-only root filesystem there is nowhere else to put it.
#
# --no-control-socket, found by the first production deploy of this image. gunicorn 26 opens a control
# socket at $HOME/.gunicorn/gunicorn.ctl, and this container has no passwd entry for uid 10001, so
# HOME is "/" — which read_only makes immutable. Every boot logged
#   [ERROR] Control server error: [Errno 30] Read-only file system: '/.gunicorn'
# The relay served correctly regardless; the cost was an ERROR on every start, in the logs an operator
# is meant to read right after a deploy — which is how a log stops being read at all.
#
# Disabled rather than relocated to /tmp. That socket can control the master process — stop, reload,
# scale — so on an image whose whole point is that a compromise reaches as little as possible, an
# admin channel nothing uses is surface to remove, not to move somewhere writable.
CMD ["gunicorn", "-b", "0.0.0.0:8080", "-w", "2", "--timeout", "30", \
     "--worker-tmp-dir", "/tmp", "--no-control-socket", "app:app"]
