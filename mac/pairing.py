"""Short-lived, Mac-approved enrollment. Discovery never carries bearer credentials.

The Mac side stays the single trust decision: by default each request waits for
the menu-bar popup, but an operator who wants zero-touch LAN pairing can drop an
`auto-approve` file into the state directory — every pending request is then
approved within one tick, still subject to the pending/device/rate limits below.
"""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import threading
import time


class Pairing:
    TTL = 120

    def __init__(self, state, pin):
        self.state, self.pin = Path(state), pin
        self.lock = threading.RLock()
        self.pending, self.last = {}, {}
        self.path = self.state / 'paired-devices.json'
        self.devices = json.loads(self.path.read_text()) if self.path.exists() else {}
        self.requests = self.state / 'pairing-requests.json'
        self.decisions = self.state / 'pairing-decisions'
        self.decisions.mkdir(exist_ok=True, mode=0o700)
        os.chmod(self.decisions, 0o700)
        for file in self.decisions.glob('*.json'):
            file.unlink()
        self._publish()

    def _write(self, path, value):
        temporary = path.with_suffix('.tmp')
        with open(temporary, 'w', opener=lambda p, flags: os.open(p, flags, 0o600)) as output:
            json.dump(value, output, ensure_ascii=False)
        os.chmod(temporary, 0o600)
        temporary.replace(path)

    def _prune(self):
        now = time.monotonic()
        self.pending = {key: value for key, value in self.pending.items() if value['expires'] > now}
        self.last = {key: value for key, value in self.last.items() if now - value < 30}

    def _publish(self):
        self._prune()
        self._write(self.requests, [
            {'id': key, 'name': value['name'], 'address': value['address'], 'code': value['code']}
            for key, value in self.pending.items() if value['state'] == 'pending'])

    def start(self, nonce, name, address):
        if not isinstance(nonce, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', nonce):
            raise ValueError('Invalid pairing nonce')
        if not isinstance(name, str) or not name or len(name) > 80 or not all(c.isprintable() for c in name):
            raise ValueError('Invalid device name')
        with self.lock:
            self._prune()
            now = time.monotonic()
            # Bound anonymous enrollment without blocking already paired file operations.
            if len(self.pending) >= 4 or len(self.devices) >= 32 or now - self.last.get(address, -100) < 10:
                return 429, {'error': 'Pairing busy; retry later'}
            self.last[address] = now
            request_id = secrets.token_hex(16)
            code = hashlib.sha256((self.pin + ':' + nonce).encode('ascii')).hexdigest()[:12].upper()
            code = '-'.join(code[i:i+4] for i in range(0, 12, 4))
            self.pending[request_id] = dict(nonce=nonce, name=name, address=address, code=code,
                                            state='pending', expires=now + self.TTL)
            self._publish()
            return 200, {'id': request_id, 'state': 'pending'}

    def poll(self, request_id, nonce):
        with self.lock:
            self._prune()
            value = self.pending.get(request_id) if isinstance(request_id, str) else None
            if not value or not isinstance(nonce, str) or not hmac.compare_digest(value['nonce'], nonce):
                return 404, {'error': 'Pairing expired or unavailable'}
            if value['state'] == 'denied':
                return 403, {'error': 'Pairing declined'}
            result = {'state': value['state']}
            if value['state'] == 'approved':
                result['token'] = value['token']
            return 200, result

    def cancel(self, request_id, nonce):
        with self.lock:
            status, _ = self.poll(request_id, nonce)
            if status not in (200, 403):
                return 404, {'error': 'Pairing unavailable'}
            self.pending.pop(request_id, None)
            if self.devices.pop(request_id, None) is not None:
                self._write(self.path, self.devices)
            self._publish()
            return 200, {'state': 'cancelled'}

    def decide(self, request_id, approved):
        with self.lock:
            self._prune()
            value = self.pending.get(request_id)
            if not value or value['state'] != 'pending':
                return False
            if approved and len(self.devices) < 32:
                token = secrets.token_urlsafe(32)
                value.update(state='approved', token=token)
            else:
                value['state'] = 'denied'
            self._publish()
            return True

    def authorized(self, token):
        return self.device(token) is not None

    def device(self, token):
        """The paired-device record for a device token, promoting a completed request on first use."""
        if not isinstance(token, str) or not token:
            return None
        with self.lock:
            for value in self.devices.values():
                if hmac.compare_digest(token, value['token']):
                    return value
            self._prune()
            for request_id, value in self.pending.items():
                if value['state'] == 'approved' and hmac.compare_digest(token, value['token']):
                    if len(self.devices) >= 32:
                        return None
                    self.devices[request_id] = {'name': value['name'], 'token': token}
                    self._write(self.path, self.devices)
                    return self.devices[request_id]
            return None

    def record_folder_key(self, token, fingerprint):
        """Bind a phone folder-share certificate fingerprint to the device that presented its token."""
        if not isinstance(fingerprint, str) or not re.fullmatch(r'[a-f0-9]{64}', fingerprint):
            raise ValueError('Invalid folder key fingerprint')
        with self.lock:
            device = self.device(token)
            if device is None:
                return False
            device['folderFp'] = fingerprint
            self._write(self.path, self.devices)
            return True

    def tick(self):
        with self.lock:
            for path in self.decisions.glob('*.json'):
                try:
                    value = json.loads(path.read_text())
                    if isinstance(value, dict) and isinstance(value.get('approved'), bool):
                        self.decide(path.stem, value['approved'])
                except (ValueError, OSError):
                    pass
                finally:
                    path.unlink(missing_ok=True)
            if (self.state / 'auto-approve').exists():
                # Zero-touch mode: approve what is still pending, under the same limits
                # (max devices, per-address spacing) that guard popup approvals.
                for request_id in [key for key, value in self.pending.items()
                                   if value['state'] == 'pending']:
                    self.decide(request_id, True)
            self._publish()
