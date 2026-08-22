"""FIXTURE — must trip khandaq-python-path-join-from-request. Never imported, never run."""
import os

from flask import request


def khandaq_fixture_traversal():
    return open(request.args.get("f"), "rb").read()
