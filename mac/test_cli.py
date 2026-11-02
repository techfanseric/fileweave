"""CLI process contract checks: isolated state, no phone or system clipboard writes."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import threading
import time
import unittest


CLI = Path(os.environ.get('FILEWEAVE_CLI_BIN', str(Path(__file__).parent / '.build/debug/FileWeaveCLI'))).resolve()


@unittest.skipUnless(CLI.is_file(), 'Build FileWeaveCLI first')
class CLITest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='fileweave-cli-process-')
        self.state = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def run_cli(self, *args, content=None):
        result = subprocess.run([str(CLI), *args, '--state', str(self.state)], input=content,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=8)
        return result.returncode, json.loads(result.stdout)

    def test_discovery_schema_and_empty_profile_do_not_initialize_state(self):
        code, value = self.run_cli('capabilities')
        self.assertEqual(code, 0)
        self.assertEqual(value['schemaVersion'], 1)
        self.assertFalse(value['data']['interactive'])
        self.assertIn('--input', value['data']['optionSchema'])
        self.assertIn('permanent_delete', value['data']['unsupported'])
        self.assertEqual(self.run_cli('devices')[1]['data']['items'], [])
        self.assertEqual(list(self.state.iterdir()), [])

    def test_errors_are_json_with_stable_exit_and_localized_messages(self):
        code, value = self.run_cli('files', 'get', '--language', 'zh-Hans')
        self.assertEqual(code, 2)
        self.assertEqual(value['error']['code'], 'invalid_arguments')
        self.assertIn('命令参数', value['error']['message'])
        code, value = self.run_cli('files', 'list')
        self.assertEqual(code, 4)
        self.assertEqual(value['error']['code'], 'not_paired')

    def test_invalid_and_oversized_stdin_never_connect_or_set_a_clipboard(self):
        code, value = self.run_cli('clipboard', 'set', '--input', '-', content=b'\xff')
        self.assertEqual(code, 2)
        self.assertEqual(value['error']['code'], 'invalid_text')
        code, value = self.run_cli('clipboard', 'set', '--input', '-', content=b'x' * (256 * 1024 + 1))
        self.assertEqual(code, 1)
        self.assertEqual(value['error']['code'], 'too_large')
        self.assertEqual(list(self.state.iterdir()), [])

    def test_chunked_multibyte_stdin_is_fully_read_before_device_resolution(self):
        process = subprocess.Popen([str(CLI), 'clipboard', 'set', '--input', '-', '--state', str(self.state)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def write():
            try:
                for byte in 'fixture 中文'.encode():
                    process.stdin.write(bytes([byte]))
                    process.stdin.flush()
                    time.sleep(0.01)
            finally:
                process.stdin.close()
        writer = threading.Thread(target=write)
        writer.start()
        try:
            process.wait(timeout=5)
            value = json.loads(process.stdout.read())
            self.assertEqual(process.returncode, 4)
            self.assertEqual(value['error']['code'], 'not_paired')
        finally:
            if process.poll() is None:
                process.kill()
            writer.join(timeout=2)
            process.stdout.close()
            process.stderr.close()

    def test_signals_cancel_blocked_stdin_with_json_and_exit_130(self):
        for number in [signal.SIGINT, signal.SIGTERM]:
            with self.subTest(signal=number):
                process = subprocess.Popen([str(CLI), 'clipboard', 'set', '--input', '-', '--state', str(self.state)],
                                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    time.sleep(0.35)
                    process.send_signal(number)
                    process.wait(timeout=4)
                    value = json.loads(process.stdout.read())
                    self.assertEqual(process.returncode, 130)
                    self.assertEqual(value['error']['code'], 'cancelled')
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.stdin.close()
                    process.stdout.close()
                    process.stderr.close()


if __name__ == '__main__':
    unittest.main()
