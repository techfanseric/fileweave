import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import ssl
import tempfile
import threading
import unittest
from bridge import Bridge
from pairing import Pairing


class PairingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.bridge = Bridge(('127.0.0.1', 0), self.temp.name)
        self.thread = threading.Thread(target=self.bridge.serve_forever, daemon=True)
        self.thread.start()
        self.context = ssl.create_default_context(cafile=str(self.bridge.cert))
        self.nonce = secrets.token_urlsafe(32)

    def tearDown(self):
        self.bridge.shutdown(); self.bridge.server_close(); self.thread.join(); self.temp.cleanup()

    def request(self, endpoint, payload=None, token=None):
        connection = http.client.HTTPSConnection('localhost', self.bridge.server_port, context=self.context)
        headers = {'Authorization': 'Bearer ' + token} if token else {}
        connection.request('POST' if payload is not None else 'GET', '/v1/' + endpoint,
                           json.dumps(payload).encode() if payload is not None else None, headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def start(self):
        status, result = self.request('pair/start', {'nonce': self.nonce, 'name': '测试手机'})
        self.assertEqual(status, 200)
        return result['id']

    def test_approval_required_nonce_bound_and_separate_persistent_token(self):
        request_id = self.start()
        payload = {'id': request_id, 'nonce': self.nonce}
        self.assertEqual(self.request('pair/poll', payload), (200, {'state': 'pending'}))
        self.assertEqual(self.request('files')[0], 401)
        self.assertEqual(self.request('pair/poll', dict(payload, nonce='B'*43))[0], 404)
        self.assertTrue(self.bridge.pairings.decide(request_id, True))
        status, result = self.request('pair/poll', payload)
        self.assertEqual(status, 200)
        self.assertNotEqual(result['token'], self.bridge.token)
        self.assertEqual(self.request('files', token=result['token'])[0], 200)
        self.assertEqual(self.request('pair/poll', payload)[1]['token'], result['token'])
        self.assertFalse(self.bridge.pairings.decide(request_id, True))
        restarted = Pairing(Path(self.temp.name), self.bridge.pin)
        self.assertTrue(restarted.authorized(result['token']))
        self.assertEqual(restarted.poll(request_id, self.nonce)[0], 404)

    def test_rejection_expiry_and_late_approval(self):
        request_id = self.start()
        self.assertTrue(self.bridge.pairings.decide(request_id, False))
        self.assertEqual(self.request('pair/poll', {'id': request_id, 'nonce': self.nonce})[0], 403)
        self.assertFalse(self.bridge.pairings.devices)
        self.bridge.pairings.pending[request_id]['expires'] = 0
        self.assertEqual(self.request('pair/poll', {'id': request_id, 'nonce': self.nonce})[0], 404)
        self.assertFalse(self.bridge.pairings.decide(request_id, True))

    def test_display_code_binds_certificate_and_nonce_without_publishing_secrets(self):
        request_id = self.start()
        view = json.loads(self.bridge.pairings.requests.read_text())[0]
        expected = hashlib.sha256((self.bridge.pin + ':' + self.nonce).encode()).hexdigest()[:12].upper()
        self.assertEqual(view['code'].replace('-', ''), expected)
        self.assertNotEqual(expected, hashlib.sha256(('0'*64 + ':' + self.nonce).encode()).hexdigest()[:12].upper())
        self.assertEqual(view['id'], request_id)
        self.assertNotIn(self.nonce, self.bridge.pairings.requests.read_text())
        self.assertNotIn(self.bridge.token, self.bridge.pairings.requests.read_text())
        self.assertEqual(os.stat(self.bridge.pairings.requests).st_mode & 0o777, 0o600)

    def test_local_decision_file_consumed_once(self):
        request_id = self.start()
        path = self.bridge.pairings.decisions / (request_id + '.json')
        path.write_text(json.dumps({'approved': True}))
        self.bridge.pairings.tick()
        self.assertFalse(path.exists())
        self.assertEqual(self.request('pair/poll', {'id': request_id, 'nonce': self.nonce})[1]['state'], 'approved')
        token = self.request('pair/poll', {'id': request_id, 'nonce': self.nonce})[1]['token']
        self.assertFalse(self.bridge.pairings.path.exists())
        self.assertEqual(self.request('files', token=token)[0], 200)
        self.assertEqual(os.stat(self.bridge.pairings.path).st_mode & 0o777, 0o600)

    def test_auto_approve_marker_approves_pending_without_dialog(self):
        request_id = self.start()
        self.bridge.pairings.tick()
        self.assertEqual(self.request('pair/poll', {'id': request_id, 'nonce': self.nonce})[1]['state'],
                         'pending')
        (Path(self.temp.name) / 'auto-approve').write_text('')
        self.bridge.pairings.tick()
        token = self.request('pair/poll', {'id': request_id, 'nonce': self.nonce})[1]['token']
        self.assertEqual(self.request('files', token=token)[0], 200)

    def test_cancel_revokes_unclaimed_approval(self):
        request_id = self.start()
        self.bridge.pairings.decide(request_id, True)
        payload = {'id': request_id, 'nonce': self.nonce}
        token = self.request('pair/poll', payload)[1]['token']
        self.assertEqual(self.request('pair/cancel', dict(payload, nonce='B'*43))[0], 404)
        self.assertEqual(self.request('pair/cancel', payload)[0], 200)
        self.assertEqual(self.request('files', token=token)[0], 401)
        self.assertEqual(self.request('pair/poll', payload)[0], 404)

    def test_rate_limit_invalid_input_and_unknown_request(self):
        for value in [{'nonce': 'bad', 'name': 'phone'}, {'nonce': self.nonce, 'name': 'bad\nname'}, []]:
            self.assertEqual(self.request('pair/start', value)[0], 400)
        self.start()
        self.assertEqual(self.request('pair/start', {'nonce': secrets.token_urlsafe(32), 'name': 'phone'})[0], 429)
        self.assertEqual(self.request('pair/poll', {'id': 'unknown', 'nonce': self.nonce})[0], 404)
        self.assertEqual(self.request('pair/poll', {'id': [], 'nonce': self.nonce})[0], 404)


if __name__ == '__main__': unittest.main()
