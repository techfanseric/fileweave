import http.client
import json
from pathlib import Path
import ssl
import tempfile
import threading
import unittest
from bridge import Bridge, MAX_EVENTS


class MemoryClipboard:
    def __init__(self): self.value = ''
    def read(self): return self.value
    def write(self, value): self.value = value


class NotifyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.server = Bridge(('127.0.0.1', 0), cls.temp.name, MemoryClipboard())
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.context = ssl.create_default_context(cafile=str(cls.server.cert))

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(); cls.temp.cleanup()

    def setUp(self):
        # 每个用例从空事件文件开始，用例之间互不影响（unittest 按方法名排序执行）。
        (Path(self.temp.name) / 'folder-events.jsonl').unlink(missing_ok=True)

    def request(self, payload, authorized=True):
        connection = http.client.HTTPSConnection('localhost', self.server.server_port, context=self.context)
        headers = {'Authorization': 'Bearer ' + self.server.token} if authorized else {}
        connection.request('POST', '/v1/notify', payload if isinstance(payload, bytes)
                           else json.dumps(payload).encode(), headers)
        response = connection.getresponse()
        status, result = response.status, response.read()
        connection.close()
        return status, result

    def events(self):
        path = Path(self.temp.name) / 'folder-events.jsonl'
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

    def test_requires_pairing(self):
        self.assertEqual(self.request({'type': 'folder-update', 'name': 'call.m4a'}, authorized=False)[0], 401)
        self.assertEqual(self.request(b'{"type":"folder-update"}', authorized=False)[0], 401)
        self.assertEqual(self.events(), [])

    def test_event_is_recorded(self):
        status, body = self.request({'type': 'folder-update', 'name': '通话录音 2026-10-22 0930.m4a'})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {'ok': True})
        events = self.events()
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]['type'], 'folder-update')
        self.assertEqual(events[0]['name'], '通话录音 2026-10-22 0930.m4a')
        self.assertGreater(events[0]['t'], 0)
        # 非法事件（未知类型、目录名、超长名字）按坏请求处理，且不落账。
        for bad in ({'type': 'other', 'name': 'a.m4a'}, {'type': 'folder-update', 'name': 'a/b.m4a'},
                    {'type': 'folder-update', 'name': 'x' * 300}, b'not json'):
            self.assertEqual(self.request(bad)[0], 400)
        self.assertEqual(len(self.events()), 1)

    def test_events_are_capped_at_limit(self):
        # 复用一条连接灌满 200+ 条事件，断言只保留最新的 MAX_EVENTS 条。
        connection = http.client.HTTPSConnection('localhost', self.server.server_port, context=self.context)
        headers = {'Authorization': 'Bearer ' + self.server.token}
        for index in range(MAX_EVENTS + 5):
            connection.request('POST', '/v1/notify',
                               json.dumps({'type': 'folder-update', 'name': f'call-{index}.m4a'}).encode(),
                               headers)
            response = connection.getresponse()
            self.assertEqual(response.status, 200)
            response.read()  # 读完响应体，keep-alive 连接才能承载下一条请求。
        connection.close()
        events = self.events()
        self.assertEqual(len(events), MAX_EVENTS)
        self.assertEqual(events[0]['name'], 'call-5.m4a')
        self.assertEqual(events[-1]['name'], f'call-{MAX_EVENTS + 4}.m4a')


if __name__ == '__main__':
    unittest.main()
