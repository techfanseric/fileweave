#!/usr/bin/env python3
"""FileWeave local Mac bridge: authenticated, certificate-pinned HTTPS; no cloud service."""
import argparse
import contextlib
import hashlib
import hmac
import json
import ipaddress
import sys
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs, urlencode
from pairing import Pairing

MAX_FILE = 8 * 1024**3
MAX_TEXT = 256 * 1024
MAX_CSR = 8 * 1024


class MacClipboard:
    def read(self):
        return subprocess.run(['/usr/bin/pbpaste'], check=True, stdout=subprocess.PIPE).stdout.decode('utf-8')

    def write(self, value):
        subprocess.run(['/usr/bin/pbcopy'], input=value.encode('utf-8'), check=True)


class Bridge(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, state, clipboard=None):
        self.state = Path(state)
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.state, 0o700)
        self.shared = self.state / 'shared'
        self.inbox = self.state / 'inbox'
        self.shared.mkdir(exist_ok=True)
        self.inbox.mkdir(exist_ok=True)
        self.clipboard = clipboard or MacClipboard()
        self.clipboard_lock = threading.Lock()
        credentials = self.state / 'credentials.json'
        if credentials.exists():
            self.token = json.loads(credentials.read_text())['token']
        else:
            self.token = secrets.token_urlsafe(32)
            credentials.write_text(json.dumps({'token': self.token}))
        os.chmod(credentials, 0o600)
        self.cert = self.state / 'certificate.pem'
        key = self.state / 'private-key.pem'
        if not self.cert.exists() or not key.exists():
            with self._restrictive_umask():
                subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '365',
                                '-subj', '/CN=FileWeave', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
                                '-addext', 'extendedKeyUsage=serverAuth',
                                '-keyout', str(key), '-out', str(self.cert)],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.chmod(key, 0o600)
        der = ssl.PEM_cert_to_DER_cert(self.cert.read_text())
        self.pin = hashlib.sha256(der).hexdigest()
        self.pairings = Pairing(self.state, self.pin)
        self.folder_lock = threading.Lock()
        super().__init__(address, Handler)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(str(self.cert), str(key))
        self.socket = ctx.wrap_socket(self.socket, server_side=True)

    def pairing(self, host):
        return 'fileweave://pair?' + urlencode({'host': host, 'port': self.server_port, 'pin': self.pin, 'token': self.token})

    def folder_ca(self):
        # A dedicated CA for phone folder-share certificates; its key never leaves this Mac.
        cert, key = self.state / 'folder-ca.crt', self.state / 'folder-ca.key'
        with self.folder_lock:
            if not cert.exists() or not key.exists():
                with self._restrictive_umask():
                    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '3650',
                                    '-subj', '/CN=FileWeave Folder CA',
                                    '-addext', 'basicConstraints=critical,CA:TRUE',
                                    '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
                                    '-keyout', str(key), '-out', str(cert)],
                                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                os.chmod(key, 0o600)
        return cert, key

    def issue_folder_certificate(self, csr):
        if not isinstance(csr, str) or 'BEGIN CERTIFICATE REQUEST' not in csr or len(csr) > MAX_CSR:
            raise ValueError('Invalid certificate request')
        ca_cert, ca_key = self.folder_ca()
        # Serialized: openssl -CAcreateserial reads and rewrites the .srl file,
        # so concurrent issuances must not race each other on it.
        with self.folder_lock:
            with tempfile.TemporaryDirectory() as workspace:
                request = Path(workspace) / 'request.csr'
                issued = Path(workspace) / 'issued.crt'
                request.write_text(csr)
                subprocess.run(['openssl', 'x509', '-req', '-in', str(request), '-CA', str(ca_cert),
                                '-CAkey', str(ca_key), '-days', '825', '-sha256', '-CAcreateserial',
                                '-out', str(issued)],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                pem = issued.read_text()
        der = ssl.PEM_cert_to_DER_cert(pem)
        return pem, hashlib.sha256(der).hexdigest()

    @staticmethod
    @contextlib.contextmanager
    def _restrictive_umask():
        # Keys land on disk with the default umask otherwise (0644 until the explicit chmod).
        previous = os.umask(0o077)
        try:
            yield
        finally:
            os.umask(previous)


class Handler(BaseHTTPRequestHandler):
    server_version = 'FileWeave/1'
    protocol_version = 'HTTP/1.1'

    def setup(self):
        super().setup()
        self.connection.settimeout(60)

    def log_message(self, *args):
        pass  # Never log file names, clipboard text, pairing tokens or Authorization headers.

    def json(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(data)

    def bearer(self):
        credential = self.headers.get('Authorization', '')
        return credential[7:] if credential.startswith('Bearer ') else ''

    def authorized(self):
        token = self.bearer()
        if not re.fullmatch(r'[A-Za-z0-9_-]{40,128}', token) or not (hmac.compare_digest(token, self.server.token) or self.server.pairings.authorized(token)):
            self.close_connection = True
            self.json(401, {'error': 'Pairing required'})
            return False
        return True

    def endpoint(self):
        url = urlsplit(self.path)
        return url.path, parse_qs(url.query)

    @staticmethod
    def safe_name(query):
        name = query.get('name', [''])[0]
        if not name or name in ('.', '..') or '/' in name or '\\' in name or '\x00' in name or name.startswith('.') or len(name.encode('utf-8')) > 240:
            raise ValueError('Invalid file name')
        return name

    def do_GET(self):
        if not self.authorized():
            return
        try:
            endpoint, query = self.endpoint()
            if endpoint == '/v1/status':
                self.json(200, {'protocol': 1, 'name': socket.gethostname(), 'maxFileBytes': MAX_FILE})
            elif endpoint == '/v1/files':
                files = [{'name': f.name, 'size': f.stat().st_size} for f in self.server.shared.iterdir()
                         if not f.name.startswith('.') and f.is_file() and not f.is_symlink()]
                self.json(200, {'files': sorted(files, key=lambda f: f['name'].casefold())})
            elif endpoint == '/v1/file':
                name = self.safe_name(query)
                file = self.server.shared / name
                if file.is_symlink() or not file.is_file():
                    self.json(404, {'error': 'File unavailable'})
                    return
                with file.open('rb') as source:
                    size = os.fstat(source.fileno()).st_size
                    start, end, status = 0, size - 1, 200
                    if self.headers.get('Range'):
                        match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers['Range'])
                        if not match:
                            self.json(416, {'error': 'Unsupported range'})
                            return
                        start = int(match[1])
                        end = min(int(match[2]) if match[2] else end, end)
                        if start > end or start >= size:
                            self.json(416, {'error': 'Range exceeds file'})
                            return
                        status = 206
                    self.send_response(status)
                    self.send_header('Content-Type', 'application/octet-stream')
                    self.send_header('Accept-Ranges', 'bytes')
                    self.send_header('Content-Length', str(max(0, end-start+1)))
                    self.send_header('Cache-Control', 'no-store')
                    if status == 206:
                        self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                    self.end_headers()
                    source.seek(start)
                    remaining = end-start+1
                    while remaining > 0:
                        data = source.read(min(remaining, 128*1024))
                        if not data:
                            break
                        self.wfile.write(data)
                        remaining -= len(data)
            elif endpoint == '/v1/clipboard':
                with self.server.clipboard_lock:
                    value = self.server.clipboard.read()
                if len(value.encode('utf-8')) > MAX_TEXT:
                    self.json(413, {'error': 'Clipboard text is too large'})
                else:
                    self.json(200, {'text': value})
            else:
                self.json(404, {'error': 'Unknown endpoint'})
        except (ValueError, OSError):
            self.close_connection = True
            self.json(400, {'error': 'Request failed'})

    def body(self, limit):
        total = 0
        transfer = self.headers.get('Transfer-Encoding', '').lower()
        if transfer and transfer != 'chunked':
            raise ValueError('Unsupported transfer encoding')
        if transfer == 'chunked':
            while True:
                line = self.rfile.readline(128)
                if len(line) >= 128 or not line.endswith(b'\r\n'):
                    raise ValueError('Malformed chunk')
                size = int(line.strip().split(b';')[0], 16)
                if size < 0 or total + size > limit:
                    raise ValueError('Body exceeds limit')
                if size == 0:
                    if self.rfile.readline(128) != b'\r\n':
                        raise ValueError('Trailers unsupported')
                    return
                remaining = size
                while remaining:
                    data = self.rfile.read(min(remaining, 128*1024))
                    if not data:
                        raise ValueError('Truncated body')
                    total += len(data)
                    remaining -= len(data)
                    yield data
                if self.rfile.read(2) != b'\r\n':
                    raise ValueError('Malformed chunk terminator')
        else:
            remaining = int(self.headers.get('Content-Length', '-1'))
            if remaining < 0 or remaining > limit:
                raise ValueError('Invalid body length')
            while remaining:
                data = self.rfile.read(min(remaining, 128*1024))
                if not data:
                    raise ValueError('Truncated body')
                remaining -= len(data)
                yield data

    def do_POST(self):
        endpoint, _ = self.endpoint()
        if endpoint in ('/v1/pair/start', '/v1/pair/poll', '/v1/pair/cancel'):
            try:
                value = json.loads(b''.join(self.body(2048)))
                if endpoint.endswith('/start'):
                    status, result = self.server.pairings.start(value['nonce'], value['name'], self.client_address[0])
                elif endpoint.endswith('/cancel'):
                    status, result = self.server.pairings.cancel(value['id'], value['nonce'])
                else:
                    status, result = self.server.pairings.poll(value['id'], value['nonce'])
                self.json(status, result)
            except (ValueError, KeyError, TypeError, OSError, UnicodeError):
                self.close_connection = True
                self.json(400, {'error': 'Invalid pairing request'})
            return
        if endpoint == '/v1/folder-key':
            token = self.bearer()
            if not self.authorized():
                return
            if hmac.compare_digest(token, self.server.token):
                self.close_connection = True
                self.json(403, {'error': 'Device pairing required'})
                return
            try:
                value = json.loads(b''.join(self.body(MAX_CSR)))
                pem, fingerprint = self.server.issue_folder_certificate(value['csr'])
                if not self.server.pairings.record_folder_key(token, fingerprint):
                    raise ValueError('Unknown paired device')
                self.json(200, {'cert': pem, 'fp': fingerprint})
            except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
                self.close_connection = True
                self.json(400, {'error': 'Certificate request failed'})
            return
        if not self.authorized():
            return
        temporary = None
        try:
            endpoint, query = self.endpoint()
            if endpoint == '/v1/clipboard':
                data = b''.join(self.body(MAX_TEXT))
                value = json.loads(data)['text']
                if not isinstance(value, str) or len(value.encode('utf-8')) > MAX_TEXT:
                    raise ValueError('Invalid clipboard text')
                with self.server.clipboard_lock:
                    self.server.clipboard.write(value)
                self.json(200, {'ok': True})
            elif endpoint == '/v1/file':
                name = self.safe_name(query)
                temporary = self.server.inbox / ('.incoming-' + secrets.token_hex(16))
                with temporary.open('xb') as dest:
                    for data in self.body(MAX_FILE):
                        dest.write(data)
                    dest.flush()
                    os.fsync(dest.fileno())
                # Hard-link atomically, never overwrite an existing received file (or a symlink).
                target = self.server.inbox / name
                try:
                    os.link(temporary, target)
                except FileExistsError:
                    target = self.server.inbox / (secrets.token_hex(4) + '-' + name)
                    os.link(temporary, target)
                self.json(201, {'name': target.name, 'ok': True})
            else:
                self.close_connection = True
                self.json(404, {'error': 'Unknown endpoint'})
        except (ValueError, KeyError, OSError, UnicodeError):
            self.close_connection = True
            self.json(400, {'error': 'Request failed or body exceeds limit'})
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def lan_ip():
    # A VPN may own the default route and return a synthetic address unreachable by the phone.
    if sys.platform == 'darwin':
        for index in range(10):
            try:
                result = subprocess.run(['/usr/sbin/ipconfig', 'getifaddr', f'en{index}'],
                                        capture_output=True, text=True, timeout=1)
                value = result.stdout.strip()
                address = ipaddress.ip_address(value)
                if not address.is_loopback and not address.is_link_local:
                    return value
            except (OSError, ValueError, subprocess.TimeoutExpired):
                continue
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('192.0.2.1', 9))
            return s.getsockname()[0]
    except OSError:
        return '127.0.0.1'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', type=Path, default=Path(__file__).parent / '.state')
    parser.add_argument('--port', type=int, default=8787)
    args = parser.parse_args()
    bridge = Bridge(('0.0.0.0', args.port), args.state)
    info = {'uri': bridge.pairing(lan_ip()), 'host': lan_ip(), 'port': bridge.server_port, 'pin': bridge.pin}
    pairing = args.state / 'pairing.json'
    pairing.write_text(json.dumps(info))
    os.chmod(pairing, 0o600)
    print(f'FileWeave Mac bridge running on HTTPS port {bridge.server_port}. Pairing is available in the menu bar.', flush=True)
    def enrollment_events():
        while True:
            bridge.pairings.tick()
            threading.Event().wait(1)
    threading.Thread(target=enrollment_events, daemon=True).start()
    try:
        bridge.serve_forever()
    finally:
        bridge.server_close()


if __name__ == '__main__':
    main()
