# Khandaq bootstrap — based on toktok/c-toxcore bootstrap_daemon docker
# Pin c-toxcore release (security audit #14)
ARG TOXCORE_TAG=v0.2.20
FROM alpine:3.21 AS build
RUN apk --no-cache add clang cmake libconfig-dev libconfig-static libsodium-dev libsodium-static \
    linux-headers musl-dev ninja python3 git
WORKDIR /src
RUN git clone --depth 1 --branch ${TOXCORE_TAG} --recurse-submodules https://github.com/toktok/c-toxcore.git c-toxcore
WORKDIR /src/c-toxcore
RUN CC=clang cmake -B _build -GNinja \
    -DCMAKE_BUILD_TYPE=Release \
    -DFULLY_STATIC=ON \
    -DBUILD_TOXAV=OFF \
    -DBOOTSTRAP_DAEMON=ON \
    -DDHT_BOOTSTRAP=OFF \
    -DUNITTEST=OFF \
    -DBUILD_MISC_TESTS=OFF \
  && cmake --build _build --target install

FROM debian:bookworm-slim
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends ca-certificates curl \
  && rm -rf /var/lib/apt/lists/* \
  && useradd --home-dir /var/lib/tox-bootstrapd --create-home --system \
     --shell /usr/sbin/nologin --user-group tox-bootstrapd \
  && chmod 700 /var/lib/tox-bootstrapd
COPY --from=build /usr/local/bin/tox-bootstrapd /usr/local/bin/
COPY bootstrap-daemon.conf /etc/tox-bootstrapd.conf
RUN chmod 644 /etc/tox-bootstrapd.conf
WORKDIR /var/lib/tox-bootstrapd
USER tox-bootstrapd
EXPOSE 33445/udp 33445/tcp 443/tcp 3389/tcp
ENTRYPOINT ["/usr/local/bin/tox-bootstrapd", "--config", "/etc/tox-bootstrapd.conf", "--log-backend", "stdout", "--foreground"]
