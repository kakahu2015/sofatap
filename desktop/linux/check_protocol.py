#!/usr/bin/env python3
"""Portable protocol/owned-child checks; no GTK window or real receiver is started."""

import base64
import json
from pathlib import Path
import queue
import struct
import tempfile
import threading
import time
import unittest
from urllib.parse import quote, urlencode

from sofatap import ACCESS_STATUSES, Backend, INPUT_STATUSES, MAXIMUM_LINE, STATUS_LABELS, pairing_uri, parse_state

PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="


def copy_uri(**changes):
    # Synthetic format fixture only; no receiver credentials or live QR.
    fields = dict(host="192.0.2.1", port="53319", token="test-only-not-a-secret-" * 2, fingerprint="0" * 64)
    fields.update(changes)
    return "sofatap://pair?" + urlencode(fields, quote_via=quote)


def snapshot(**changes):
    value = dict(type="state", name="Test computer", platform="linux", inputStatus="dry_run", dryRun=True,
                 connected=False, pairings=[dict(host="192.0.2.1", uri="sofatap://test-only", qrPng=PNG)])
    value.update(changes)
    return json.dumps(value)


class ProtocolChecks(unittest.TestCase):
    def test_invalid_or_missing_optional_uri_preserves_older_qr_snapshots(self):
        state = parse_state(snapshot())
        self.assertEqual(state.pairings[0].host, "192.0.2.1")
        self.assertEqual(state.pairings[0].png, base64.b64decode(PNG))
        self.assertIsNone(state.pairings[0].uri)
        for uri in (None, 12, [], "not-a-link", "x" * 2049):
            with self.subTest(uri_type=type(uri).__name__):
                pairing = dict(host="192.0.2.1", qrPng=PNG)
                if uri is not None:
                    pairing["uri"] = uri
                state = parse_state(snapshot(pairings=[pairing]))
                self.assertIsNone(state.pairings[0].uri)
                self.assertEqual(state.pairings[0].png, base64.b64decode(PNG))

    def test_copy_payload_matches_the_selected_host_and_phone_contract(self):
        for host, selected in (("192.0.2.1", "192.0.2.1"), ("receiver.local", "RECEIVER.LOCAL"),
                               ("2001:db8::17", "2001:0db8:0:0:0:0:0:17")):
            with self.subTest(host=host):
                uri = copy_uri(host=host)
                state = parse_state(snapshot(pairings=[dict(host=selected, qrPng=PNG, uri=uri)]))
                self.assertEqual(state.pairings[0].uri, uri)
                self.assertNotIn(uri, repr(state))
                self.assertNotIn("test-only-not-a-secret", repr(state))

    def test_copy_payload_rejects_ambiguous_and_unsafe_links(self):
        valid = copy_uri()
        invalid = [
            copy_uri(host="192.0.2.2"), copy_uri(host="bad_name"), copy_uri(host="fe80::1%eth0"),
            copy_uri(port="0"), copy_uri(port="65536"), copy_uri(port="+80"), copy_uri(port="80.0"),
            copy_uri(token="short"), copy_uri(token="a" * 257), copy_uri(token="a" * 31 + "+"),
            copy_uri(fingerprint="0" * 63), copy_uri(fingerprint="x" * 64),
            valid + "&host=192.0.2.1", valid.replace("token=", "extra="),
            valid.replace("sofatap:", "https:"), valid.replace("//pair?", "//pair/path?"),
            valid.replace("//pair?", "//user@pair?"), valid.replace("//pair?", "//pair:80?"),
            valid + "#fragment", valid + "#", valid + "\n", valid.replace("192.0.2.1", "%GG"),
            valid.replace("192.0.2.1", "192.0.2.1%00"), valid.replace("192.0.2.1", "%FF"),
            valid.replace("192.0.2.1", "测试"),
            copy_uri(platform="webos"), copy_uri(platform="unknown"), copy_uri(platform=""),
            valid + "&platform=linux&platform=windows", valid + "&name=TV&name=TV", valid + "&na%6De=TV",
            copy_uri(name=""), copy_uri(name=" \u00a0"), copy_uri(name="a" * 81), copy_uri(name="😀" * 65),
            copy_uri(name="e\u0301" * 41), copy_uri(name="TV\n"), copy_uri(name="TV\x00"), copy_uri(name="TV\u0085"),
            copy_uri(name="TV\u200b"), copy_uri(name="TV\u202e"), valid + "&name=%FF", valid + "&name=%ED%A0%80",
        ]
        for index, uri in enumerate(invalid):
            with self.subTest(case=index):
                self.assertIsNone(pairing_uri(uri, "192.0.2.1"))
                state = parse_state(snapshot(pairings=[dict(host="192.0.2.1", qrPng=PNG, uri=uri)]))
                self.assertIsNone(state.pairings[0].uri)
                self.assertEqual(state.pairings[0].png, base64.b64decode(PNG))

    def test_copy_payload_accepts_optional_bounded_metadata(self):
        for changes in ({"platform": "windows"}, {"platform": "macos"}, {"platform": "linux"},
                        {"platform": "linux", "name": "\u00a0 客厅 + TV 🛋️ \u3000"},
                        {"name": "a" * 80}, {"name": "客" * 80}, {"name": "😀" * 64}):
            uri = copy_uri(**changes)
            self.assertEqual(pairing_uri(uri, "192.0.2.1"), uri)
        literal_plus = copy_uri(name="+").replace("%2B", "+")
        self.assertEqual(pairing_uri(literal_plus, "192.0.2.1"), literal_plus)

    def test_accepts_live_input_statuses(self):
        for status in INPUT_STATUSES - {"dry_run"}:
            with self.subTest(status=status):
                state = parse_state(snapshot(dryRun=False, inputStatus=status), expected_dry_run=False)
                self.assertFalse(state.dry_run)
                self.assertEqual(state.input_status, status)
                self.assertTrue(STATUS_LABELS[status])
        self.assertEqual(ACCESS_STATUSES, {"permission_required", "permission_denied", "input_unavailable"})
        self.assertNotIn("permission_pending", ACCESS_STATUSES)

    def test_rejects_wrong_platform_unknown_status_and_conflicting_mode(self):
        for changes in ({"platform": "macos"}, {"platform": None}, {"inputStatus": "unknown"},
                        {"inputStatus": []}, {"inputStatus": "ready"}, {"dryRun": False}, {"dryRun": 1}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                parse_state(snapshot(**changes))
        with self.assertRaises(ValueError):
            parse_state(snapshot(), expected_dry_run=False)
        with self.assertRaises(ValueError):
            parse_state(snapshot(dryRun=False, inputStatus="ready"), expected_dry_run=True)

    def test_rejects_bad_message_and_types(self):
        for line in ("not-json", "[]", snapshot(type="other"), snapshot(connected="false"), snapshot(name="")):
            with self.subTest(line=line):
                with self.assertRaises(ValueError):
                    parse_state(line)

    def test_bounds_private_frames_and_images(self):
        huge_png = bytearray(base64.b64decode(PNG))
        huge_png[16:20] = struct.pack(">I", 65535)
        for line in ("x" * (MAXIMUM_LINE + 1), snapshot(pairings=[dict(host="x", qrPng=PNG)] * 33),
                     snapshot(pairings=[dict(host="x", qrPng="not-png")]),
                     snapshot(pairings=[dict(host="x", qrPng=base64.b64encode(huge_png).decode())])):
            with self.assertRaises(ValueError):
                parse_state(line)

    def test_owned_child_refresh_and_graceful_quit(self):
        with tempfile.TemporaryDirectory(prefix="sofatap-gtk-pipe-") as temp:
            executable = Path(temp) / "backend"
            executable.write_text("#!/usr/bin/env python3\nimport json,sys\nassert '--native-ui' in sys.argv and '--dry-run' in sys.argv\nstate=json.loads(" + repr(snapshot()) + ")\nprint(json.dumps(state),flush=True)\nfor line in sys.stdin:\n command=json.loads(line)['type']\n if command=='quit': break\n if command=='refresh':\n  state['connected']=True\n  print(json.dumps(state),flush=True)\n")
            executable.chmod(0o700)
            received, failures = queue.Queue(), queue.Queue()
            backend = Backend(executable, received.put, lambda: failures.put(True), dry_run=True)
            try:
                backend.start()
                self.assertFalse(received.get(timeout=5).connected)
                backend.send("refresh")
                self.assertTrue(received.get(timeout=5).connected)
                backend.stop()
                self.assertEqual(backend.process.poll(), 0)
                self.assertTrue(failures.empty())
                backend.stop()
            finally:
                backend.stop()


    def test_default_real_backend_waits_for_explicit_access_request(self):
        with tempfile.TemporaryDirectory(prefix="sofatap-gtk-access-") as temp:
            executable = Path(temp) / "backend"
            executable.write_text("#!/usr/bin/env python3\nimport json,sys\nassert '--native-ui' in sys.argv and '--dry-run' not in sys.argv\nstate=json.loads(" +
                                  repr(snapshot(dryRun=False, inputStatus="permission_required")) +
                                  ")\nprint(json.dumps(state),flush=True)\nfor line in sys.stdin:\n command=json.loads(line)['type']\n if command=='quit': break\n if command=='requestAccess': state['inputStatus']='permission_pending'\n print(json.dumps(state),flush=True)\n")
            executable.chmod(0o700)
            received, failures = queue.Queue(), queue.Queue()
            backend = Backend(executable, received.put, lambda: failures.put(True))
            try:
                backend.start()
                self.assertEqual(received.get(timeout=5).input_status, "permission_required")
                backend.send("refresh")
                self.assertEqual(received.get(timeout=5).input_status, "permission_required")
                backend.send("requestAccess")
                self.assertEqual(received.get(timeout=5).input_status, "permission_pending")
                backend.stop()
                self.assertEqual(backend.process.poll(), 0)
                self.assertTrue(failures.empty())
            finally:
                backend.stop()

    def test_wrong_mode_fails_closed_and_stops_backend(self):
        with tempfile.TemporaryDirectory(prefix="sofatap-gtk-mode-") as temp:
            executable = Path(temp) / "backend"
            executable.write_text("#!/usr/bin/env python3\nimport json,sys\nprint(" + repr(snapshot()) +
                                  ",flush=True)\nfor line in sys.stdin:\n if json.loads(line)['type']=='quit': break\n")
            executable.chmod(0o700)
            failures = queue.Queue()
            backend = Backend(executable, lambda _: self.fail("wrong-mode state accepted"), lambda: failures.put(True))
            try:
                backend.start()
                self.assertTrue(failures.get(timeout=5))
                self.assertEqual(backend.process.wait(timeout=7), 0)
            finally:
                backend.stop()

    def test_broken_state_channel_stops_owned_child(self):
        for failure in ("malformed", "eof", "oversized"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(prefix="sofatap-gtk-failure-") as temp:
                executable = Path(temp) / "backend"
                fail = {"malformed": "print('not-json',flush=True)\n", "eof": "os.close(1)\n",
                        "oversized": "sys.stdout.write('x'*" + str(MAXIMUM_LINE + 1) + ");sys.stdout.flush()\n"}[failure]
                executable.write_text("#!/usr/bin/env python3\nimport json,os,sys\n" + fail +
                                      "for line in sys.stdin:\n if json.loads(line)['type']=='quit': break\n")
                executable.chmod(0o700)
                failures = queue.Queue()
                backend = Backend(executable, lambda _: self.fail("unexpected state"), lambda: failures.put(True))
                try:
                    backend.start()
                    self.assertTrue(failures.get(timeout=5))
                    self.assertEqual(backend.process.wait(timeout=7), 0)
                finally:
                    backend.stop()

    def test_shutdown_bounds_blocked_writer(self):
        with tempfile.TemporaryDirectory(prefix="sofatap-gtk-blocked-") as temp:
            executable = Path(temp) / "backend"
            executable.write_text("#!/usr/bin/env python3\nimport signal,time\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\nprint(" +
                                  repr(snapshot()) + ",flush=True)\ntime.sleep(60)\n")
            executable.chmod(0o700)
            received = queue.Queue()
            backend = Backend(executable, received.put, lambda: None, dry_run=True)
            sent = []
            def fill_pipe():
                try:
                    while True:
                        backend.send("refresh")
                        sent.append(True)
                except (OSError, ValueError):
                    pass
            try:
                backend.start()
                received.get(timeout=5)
                writer = threading.Thread(target=fill_pipe, daemon=True)
                writer.start()
                deadline = time.monotonic() + 3
                stable = False
                while time.monotonic() < deadline:
                    before = len(sent)
                    time.sleep(0.1)
                    if before > 0 and before == len(sent) and writer.is_alive():
                        stable = True
                        break
                self.assertTrue(stable, "fixture must fill pipe and block writer before shutdown")
                started = time.monotonic()
                backend.stop()
                self.assertLess(time.monotonic() - started, 13)
                self.assertIsNotNone(backend.process.poll())
                writer.join(timeout=2)
                self.assertFalse(writer.is_alive())
            finally:
                if backend.process is not None and backend.process.poll() is None:
                    backend.process.kill()
                    backend.process.wait(timeout=3)
                backend.stop()


if __name__ == "__main__":
    unittest.main()
