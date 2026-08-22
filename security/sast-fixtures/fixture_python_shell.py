"""FIXTURE — must trip khandaq-python-subprocess-shell-true. Never imported, never run."""
import os
import subprocess


def khandaq_fixture_shell(host):
    subprocess.run("ssh " + host + " uptime", shell=True)
    os.system("rm -rf " + host)
