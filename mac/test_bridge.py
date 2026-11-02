import http.client
import hashlib
import json
from pathlib import Path
import ssl
import tempfile
import threading
import unittest
from bridge import Bridge


class MemoryClipboard:
    def __init__(self): self.value = ''
    def read(self): return self.value
    def write(self, value): self.value = value


class BridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.server = Bridge(('127.0.0.1', 0), cls.temp.name, MemoryClipboard())
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.context = ssl.create_default_context(cafile=str(cls.server.cert))
        (cls.server.shared / 'sample.mp4').write_bytes(bytes(range(256)) * 20)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(); cls.temp.cleanup()

    def request(self, method, path, body=None, headers=None, authorized=True, chunked=False):
        conn = http.client.HTTPSConnection('localhost', self.server.server_port, context=self.context)
        h = {'Authorization': 'Bearer ' + self.server.token} if authorized else {}
        h.update(headers or {})
        conn.request(method, path, body, h, encode_chunked=chunked)
        response = conn.getresponse()
        status, result = response.status, response.read()
        conn.close()
        return status, result

    def test_highlight_requires_pairing_and_contains_no_bearer(self):
        self.assertEqual(self.request('POST', '/v1/highlight', b'{}', authorized=False)[0], 401)
        self.assertEqual(self.request('POST', '/v1/highlight', b'{}')[0], 200)
        event = json.loads((self.server.state / 'highlight.json').read_text())
        self.assertEqual(len(event['device']), 64)
        self.assertNotIn(self.server.token, json.dumps(event))
        first = event['id']
        self.assertEqual(self.request('POST', '/v1/highlight', b'{}')[0], 200)
        self.assertNotEqual(first, json.loads((self.server.state / 'highlight.json').read_text())['id'])

    def test_requires_pairing(self):
        self.assertEqual(self.request('GET', '/v1/status', authorized=False)[0], 401)
        self.assertEqual(self.request('POST', '/v1/clipboard', b'{}', authorized=False)[0], 401)

    def test_range_playback(self):
        status, result = self.request('GET', '/v1/file?name=sample.mp4', headers={'Range': 'bytes=250-270'})
        self.assertEqual(status, 206)
        self.assertEqual(result, (bytes(range(256))*20)[250:271])
        self.assertEqual(self.request('GET', '/v1/file?name=sample.mp4', headers={'Range': 'bytes=99999-'})[0], 416)

    def test_traversal_and_symlink(self):
        for name in ['../credentials.json', '%2Fetc%2Fpasswd', '..', '.hidden']:
            self.assertEqual(self.request('GET', '/v1/file?name='+name)[0], 400)
        link = self.server.shared / 'secret-link'
        link.symlink_to(self.server.state/'credentials.json')
        self.assertEqual(self.request('GET', '/v1/file?name=secret-link')[0], 404)
        link.unlink()

    def test_upload_chunked_and_collision(self):
        self.assertEqual(self.request('POST', '/v1/file?name=hello.txt', [b'hello', b' world'], chunked=True)[0], 201)
        self.assertEqual((self.server.inbox/'hello.txt').read_bytes(), b'hello world')
        status, data = self.request('POST', '/v1/file?name=hello.txt', b'second')
        self.assertEqual(status, 201)
        self.assertNotEqual(json.loads(data)['name'], 'hello.txt')
        self.assertEqual((self.server.inbox/'hello.txt').read_bytes(), b'hello world')
        self.assertFalse(list(self.server.inbox.glob('.incoming-*')))

    def test_clipboard_unicode(self):
        text = 'FileWeave 中文 🧵'
        self.assertEqual(self.request('POST', '/v1/clipboard', json.dumps({'text': text}).encode())[0], 200)
        self.assertEqual(json.loads(self.request('GET', '/v1/clipboard')[1])['text'], text)

    def test_latest_received_clipboard_is_memory_only_and_not_the_mac_clipboard(self):
        text = 'phone test clipboard 中文'
        self.assertEqual(self.request('POST', '/v1/clipboard', json.dumps({'text': text}).encode())[0], 200)
        first = json.loads(self.request('GET', '/v1/received-clipboard')[1])['clipboard']
        self.assertEqual(first['text'], text)
        self.server.clipboard.write('later Mac copy')
        self.assertEqual(json.loads(self.request('GET', '/v1/received-clipboard')[1])['clipboard'], first)
        self.request('POST', '/v1/clipboard', json.dumps({'text': 'next phone copy'}).encode())
        second = json.loads(self.request('GET', '/v1/received-clipboard')[1])['clipboard']
        self.assertNotEqual(first['id'], second['id'])
        self.assertEqual(second['text'], 'next phone copy')
        self.assertEqual(self.request('GET', '/v1/received-clipboard', authorized=False)[0], 401)
        self.assertFalse(any('clipboard' in path.name for path in self.server.state.rglob('*')))

    def test_authenticated_address_snapshot_contains_no_tokens(self):
        device = hashlib.sha256(b'test token never persisted').hexdigest()
        self.server.note_authenticated_peer(device, '192.168.10.9')
        snapshot = json.loads((self.server.state / 'active-peers.json').read_text())
        self.assertEqual(snapshot[0]['host'], '192.168.10.9')
        self.assertNotIn('test token never persisted', json.dumps(snapshot))
        self.assertEqual((self.server.state / 'active-peers.json').stat().st_mode & 0o777, 0o600)
        self.server.note_authenticated_peer('ignored', '127.0.0.1')
        self.assertEqual(snapshot, json.loads((self.server.state / 'active-peers.json').read_text()))

    def test_reject_oversize_before_read(self):
        self.assertEqual(self.request('POST', '/v1/file?name=oversize', b'', {'Content-Length': str(9*1024**3)})[0], 400)
        self.assertFalse(list(self.server.inbox.glob('.incoming-*')))


if __name__ == '__main__': unittest.main()
