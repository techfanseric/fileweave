#!/usr/bin/env python3
"""Verify automatic list updates and Mac service recovery using an isolated generated file.

Requires a running FileWeave Mac and an already paired debug Android app. Never reads
clipboard contents or changes existing shared files. Terminates only this app's Python
service once, so the Mac application's automatic restart can be verified.
"""
import argparse
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('serial')
parser.add_argument('--adb-port', type=int, default=5037)
args = parser.parse_args()
repo = Path(__file__).resolve().parent.parent
executable = repo / 'mac/build/FileWeave Mac.app/Contents/MacOS/FileWeaveMac'
app_pid = int(subprocess.check_output(['pgrep', '-f', '^' + re.escape(str(executable)) + '$'], text=True).strip())


def server_pid():
    result = subprocess.run(['pgrep', '-P', str(app_pid)], capture_output=True, text=True)
    for pid in result.stdout.split():
        try:
            command = subprocess.check_output(['ps', '-p', pid, '-o', 'command='], text=True)
        except subprocess.CalledProcessError:
            continue
        if str(repo / 'mac/bridge.py') in command:
            return int(pid)
    return None


fixture = repo / 'mac/.state/shared' / ('fileweave-qa-' + secrets.token_hex(6) + '.txt')
assert not fixture.exists()
owned = False
command = ['adb', '-P', str(args.adb_port), '-s', args.serial, 'shell', 'am', 'instrument', '-w',
           '-e', 'fixture', fixture.name,
           'org.fileweave.android/me.zhanghai.android.files.diagnostics.BridgeDiscoveryInstrumentation']
process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
lines = []
try:
    for line in process.stdout:
        lines.append(line)
        print(line, end='', flush=True)
        if 'QA_RESTART' in line:
            before = server_pid()
            assert before is not None, 'FileWeave Mac service is not running'
            os.kill(before, signal.SIGTERM)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                current = server_pid()
                if current is not None and current != before:
                    break
                time.sleep(.2)
            else:
                raise RuntimeError('Mac service did not restart automatically')
            print('Mac service restarted automatically: PASS', flush=True)
            with fixture.open('x') as output:
                output.write('Generated FileWeave automatic refresh check\n')
            owned = True
        elif 'QA_ADD' in line:
            with fixture.open('x') as output:
                output.write('Generated FileWeave automatic refresh check\n')
            owned = True
        elif 'QA_REMOVE' in line and owned:
            fixture.unlink()
            owned = False
    assert process.wait() == 0
    output = ''.join(lines)
    assert 'FAILED:' not in output and 'Automatic list add/remove, Mac service crash recovery and simplified controls: PASS' in output
finally:
    if owned:
        fixture.unlink(missing_ok=True)
    if process.poll() is None:
        process.terminate()
