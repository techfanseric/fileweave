#!/usr/bin/env python3
"""Run isolated debug checks. No user files or real clipboard contents are read by these checks."""
import argparse
import json
from pathlib import Path
import subprocess
from urllib.parse import urlsplit, parse_qs, urlencode

parser = argparse.ArgumentParser()
parser.add_argument('serial')
parser.add_argument('--video', type=Path, required=True)
parser.add_argument('--pairing', type=Path)
parser.add_argument('--host', help='Override the host for emulator/USB test transport only')
args = parser.parse_args()
repo = Path(__file__).resolve().parent.parent

def adb(*command, **kwargs):
    return subprocess.run(['adb', '-s', args.serial, *command], check=True, **kwargs)

adb('install', '-r', str(repo/'app/build/outputs/apk/debug/app-debug.apk'))
adb('push', str(args.video), '/data/local/tmp/fileweave-qa.mp4')
adb('shell', 'run-as', 'org.fileweave.android', 'mkdir', '-p', 'cache')
adb('shell', 'run-as', 'org.fileweave.android', 'cp', '/data/local/tmp/fileweave-qa.mp4', 'cache/fileweave-qa.mp4')
if args.pairing:
    uri = json.loads(args.pairing.read_text())['uri']
    if args.host:
        query = parse_qs(urlsplit(uri).query)
        query['host'] = [args.host]
        uri = 'fileweave://pair?' + urlencode({key: values[0] for key, values in query.items()})
    adb('shell', 'run-as', 'org.fileweave.android', 'tee', 'cache/qa-pairing.txt', input=uri.encode(), stdout=subprocess.DEVNULL)
result = adb('shell', 'am', 'instrument', '-w', 'org.fileweave.android/me.zhanghai.android.files.diagnostics.SmokeInstrumentation', capture_output=True, text=True)
print(result.stdout)
if 'FAILED:' in result.stdout or 'INSTRUMENTATION_FAILED' in result.stdout or 'lock revocation: PASS' not in result.stdout:
    raise SystemExit(1)
