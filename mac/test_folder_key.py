import hashlib
import http.client
import json
from pathlib import Path
import secrets
import ssl
import subprocess
import tempfile
import threading
import unittest
from bridge import Bridge


class FolderKeyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.bridge = Bridge(('127.0.0.1', 0), self.temp.name)
        self.thread = threading.Thread(target=self.bridge.serve_forever, daemon=True)
        self.thread.start()
        self.context = ssl.create_default_context(cafile=str(self.bridge.cert))
        self.nonce = secrets.token_urlsafe(32)

    def tearDown(self):
        self.bridge.shutdown(); self.bridge.server_close(); self.thread.join(); self.temp.cleanup()

    def request(self, payload, token=None):
        connection = http.client.HTTPSConnection('localhost', self.bridge.server_port, context=self.context)
        headers = {'Authorization': 'Bearer ' + token} if token else {}
        connection.request('POST', '/v1/folder-key', json.dumps(payload).encode(), headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def paired_token(self):
        connection = http.client.HTTPSConnection('localhost', self.bridge.server_port, context=self.context)
        connection.request('POST', '/v1/pair/start',
                           json.dumps({'nonce': self.nonce, 'name': '测试手机'}).encode())
        request_id = json.loads(connection.getresponse().read())['id']
        connection.close()
        self.bridge.pairings.decide(request_id, True)
        connection = http.client.HTTPSConnection('localhost', self.bridge.server_port, context=self.context)
        connection.request('POST', '/v1/pair/poll',
                           json.dumps({'id': request_id, 'nonce': self.nonce}).encode())
        token = json.loads(connection.getresponse().read())['token']
        connection.close()
        return token

    def make_csr(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        request = Path(workspace.name) / 'request.csr'
        subprocess.run(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes',
                        '-subj', '/CN=FileWeave Folder Test',
                        '-keyout', str(Path(workspace.name) / 'key.pem'), '-out', str(request)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return request.read_text()

    def test_issuance_records_fingerprint_and_chains_to_dedicated_ca(self):
        token = self.paired_token()
        status, result = self.request({'csr': self.make_csr()}, token)
        self.assertEqual(status, 200)
        self.assertEqual(result['fp'], hashlib.sha256(ssl.PEM_cert_to_DER_cert(result['cert'])).hexdigest())
        ca_cert, _ = self.bridge.folder_ca()
        with tempfile.TemporaryDirectory() as workspace:
            issued = Path(workspace) / 'issued.crt'
            issued.write_text(result['cert'])
            self.assertEqual(subprocess.run(['openssl', 'verify', '-CAfile', str(ca_cert), str(issued)],
                                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode, 0)
        devices = json.loads((Path(self.temp.name) / 'paired-devices.json').read_text())
        self.assertTrue(any(device.get('folderFp') == result['fp'] for device in devices.values()))

    def test_reissue_replaces_recorded_fingerprint(self):
        token = self.paired_token()
        csr = self.make_csr()
        _, first = self.request({'csr': csr}, token)
        status, second = self.request({'csr': csr}, token)
        self.assertEqual(status, 200)
        self.assertNotEqual(first['fp'], second['fp'])
        devices = json.loads((Path(self.temp.name) / 'paired-devices.json').read_text())
        # One active fingerprint per device: the latest issuance wins.
        fingerprints = {device.get('folderFp') for device in devices.values()}
        self.assertEqual(fingerprints, {second['fp']})

    def test_requires_device_token(self):
        self.assertEqual(self.request({'csr': self.make_csr()})[0], 401)
        self.assertEqual(self.request({'csr': self.make_csr()}, token='A' * 43)[0], 401)
        self.assertEqual(self.request({'csr': self.make_csr()}, token=self.bridge.token)[0], 403)

    def test_rejects_garbage_request(self):
        token = self.paired_token()
        self.assertEqual(self.request({'csr': 'not a csr'}, token)[0], 400)
        self.assertEqual(self.request({}, token)[0], 400)

    def test_rejects_oversize_request(self):
        token = self.paired_token()
        self.assertEqual(self.request({'csr': 'A' * 9000}, token)[0], 400)

    def test_concurrent_issuances_get_distinct_certificates(self):
        token = self.paired_token()
        csrs = [self.make_csr() for _ in range(4)]
        results = []
        def issue(csr):
            results.append(self.request({'csr': csr}, token))
        threads = [threading.Thread(target=issue, args=(csr,)) for csr in csrs]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        self.assertEqual([status for status, _ in results], [200] * 4)
        fingerprints = [result['fp'] for _, result in results]
        self.assertEqual(len(set(fingerprints)), 4)


if __name__ == '__main__':
    unittest.main()
