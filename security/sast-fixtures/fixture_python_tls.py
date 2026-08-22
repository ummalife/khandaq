"""Deliberately vulnerable. Not built, not shipped, not imported — see README.md in this directory.

Fires: khandaq-python-obsolete-tls.

A client that lowers its own floor to TLS 1.0 accepts a protocol with known-broken record integrity,
and does so silently: the connection succeeds, so nothing in the logs says the traffic was downgraded.
"""
import socket
import ssl


def fetch_insecurely(host: str) -> bytes:
    ctx = ssl.create_default_context()
    ctx.minimum_version = ssl.TLSVersion.TLSv1          # <-- finding
    ctx.maximum_version = ssl.TLSVersion.TLSv1_1        # <-- finding
    with socket.create_connection((host, 443), timeout=10) as sock:
        with ctx.wrap_socket(sock, server_hostname=host) as tls:
            tls.sendall(b"GET / HTTP/1.0\r\n\r\n")
            return tls.recv(4096)
