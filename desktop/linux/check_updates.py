#!/usr/bin/env python3
"""Update checks use private temporary packages; no network or desktop input."""
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from receiver_update import ReceiverUpdater, Release, select_release, unpack, version_key, verify_archive


class UpdateChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.package = self.root / 'SofaTap-Linux-x64'
        self.package.mkdir()
        (self.package / 'BUILD-MANIFEST.json').write_text(json.dumps({'runtime': 'linux-x64', 'updateVersion': '0.8.0-beta.1', 'files': {'old.txt': 'fixture'}}))
        (self.package / 'old.txt').write_text('old executable and state')
        self.updater = ReceiverUpdater(self.package, self.root / 'settings/updates.json', self.root / 'cache')
        validator = patch.object(self.updater, 'validate_runtime')
        self.runtime_validation = validator.start()
        self.addCleanup(validator.stop)

    def bundle(self, extra=None, manifest=None):
        archive = self.root / 'download.tar.gz'
        entries = {
            'BUILD-MANIFEST.json': json.dumps(manifest or {'runtime': 'linux-x64', 'updateVersion': '0.8.1-beta.1'}).encode(),
            'sofatap': b'#!/bin/sh\nexit 0\n', 'lib/SofaTapHost': b'ELF test fixture',
            'lib/linux/sofatap.py': b'# test', 'lib/linux/receiver_update.py': b'# test',
        }
        with tarfile.open(archive, 'w:gz') as bundle:
            for name, data in entries.items():
                member = tarfile.TarInfo('SofaTap-Linux-x64/' + name)
                member.size = len(data)
                member.mode = 0o755 if name in ('sofatap', 'lib/SofaTapHost') else 0o644
                bundle.addfile(member, io.BytesIO(data))
            if extra:
                bundle.addfile(extra)
        release = Release('0.8.1-beta.1', 'linux-x64',
            'https://github.com/chekusu/sofatap/releases/download/v0.8.1-beta.1/SofaTap-Linux-x64.tar.gz',
            archive.stat().st_size, hashlib.sha256(archive.read_bytes()).hexdigest())
        return archive, release

    def stage(self):
        archive, release = self.bundle()
        self.updater.cache.mkdir(parents=True)
        (self.updater.cache / 'package.tar.gz').write_bytes(archive.read_bytes())
        (self.updater.cache / 'pending.json').write_text(json.dumps(release.__dict__))
        return release

    def test_semantic_order_and_stable(self):
        self.assertGreater(version_key('0.10.0-beta.1'), version_key('0.9.9'))
        self.assertGreater(version_key('1.0.0'), version_key('1.0.0-rc.9'))
        with self.assertRaises(ValueError):
            version_key('not-a-version')

    def test_catalog_filters_stream_architecture_digest_and_downgrade(self):
        archive, release = self.bundle()
        asset = {'name': 'SofaTap-Linux-x64.tar.gz', 'state': 'uploaded', 'size': release.size,
                 'digest': 'sha256:' + release.digest, 'browser_download_url': release.url}
        row = {'tag_name': 'v0.8.1-beta.1', 'prerelease': True, 'assets': [asset]}
        self.assertEqual(select_release([row], '0.8.0-beta.1', 'linux-x64'), release)
        self.assertIsNone(select_release([row], '0.9.0-beta.1', 'linux-x64'))
        self.assertIsNone(select_release([row], '0.8.0', 'linux-x64'))
        self.assertIsNone(select_release([{**row, 'prerelease': False}], '0.8.0', 'linux-x64'))
        self.assertIsNone(select_release([row], '0.8.0-beta.1', 'linux-arm64'))
        for changed in ({'digest': None}, {'size': 0}, {'browser_download_url': 'https://evil.example/update'}, {'state': 'new'}):
            with self.assertRaises(ValueError):
                select_release([{**row, 'assets': [{**asset, **changed}]}], '0.8.0-beta.1', 'linux-x64')

    def test_truncated_or_modified_download_is_rejected(self):
        archive, release = self.bundle()
        archive.write_bytes(archive.read_bytes()[:-1])
        with self.assertRaises(ValueError):
            verify_archive(archive, release)

    def test_traversal_symlink_special_and_duplicate_entries_rejected(self):
        for name, type_ in [('SofaTap-Linux-x64/../../escape', tarfile.DIRTYPE),
                            ('SofaTap-Linux-x64/link', tarfile.SYMTYPE),
                            ('SofaTap-Linux-x64/pipe', tarfile.FIFOTYPE),
                            ('SofaTap-Linux-x64/sofatap', tarfile.REGTYPE)]:
            with self.subTest(name=name):
                member = tarfile.TarInfo(name)
                member.type = type_
                member.linkname = '/tmp'
                archive, release = self.bundle(member)
                with tempfile.TemporaryDirectory(dir=self.root) as destination:
                    with self.assertRaises((ValueError, FileExistsError)):
                        unpack(archive, release, destination)
        self.assertFalse((self.root / 'escape').exists())

    def test_manifest_version_and_architecture_are_bound(self):
        for manifest in ({'runtime': 'linux-arm64', 'updateVersion': '0.8.1-beta.1'},
                         {'runtime': 'linux-x64', 'updateVersion': '0.7.0'}):
            archive, release = self.bundle(manifest=manifest)
            with tempfile.TemporaryDirectory(dir=self.root) as destination:
                with self.assertRaises(ValueError):
                    unpack(archive, release, destination)

    def test_default_on_optout_persists_and_prevents_background_network_or_apply(self):
        self.assertTrue(self.updater.preferences()['automatic'])
        self.stage()
        self.updater.set_automatic(False)
        with patch('receiver_update.fetch_releases', side_effect=AssertionError('network')):
            self.updater.check()
        self.assertFalse(self.updater.apply_pending())
        self.assertTrue((self.package / 'old.txt').exists())
        self.assertFalse(ReceiverUpdater(self.package, self.updater.settings, self.root / 'cache').preferences()['automatic'])

    def test_success_retains_old_package_and_external_pairing_data(self):
        self.stage()
        pairing = self.root / 'settings/pairing.dat'
        pairing.parent.mkdir()
        pairing.write_bytes(b'private pairing fixture')
        self.assertTrue(self.updater.apply_pending())
        self.assertTrue((self.package / 'sofatap').exists())
        self.assertEqual(pairing.read_bytes(), b'private pairing fixture')
        backups = list(self.root.glob('SofaTap-Linux-x64.backup-*'))
        self.assertEqual(len(backups), 1)
        self.assertEqual((backups[0] / 'old.txt').read_text(), 'old executable and state')
        self.assertIsNone(self.updater.pending())

    def test_replace_failure_rolls_back_existing_installation(self):
        self.stage()
        replace = os.replace
        def failing(source, target):
            if Path(source).name.startswith('.sofatap-update-'):
                raise OSError('simulated rename failure')
            return replace(source, target)
        with patch('receiver_update.os.replace', side_effect=failing):
            with self.assertRaises(OSError):
                self.updater.apply_pending()
        self.assertTrue((self.package / 'old.txt').exists())
        self.assertIsNotNone(self.updater.pending())

    def test_corrupt_pending_never_replaces_installed_package(self):
        self.stage()
        (self.updater.cache / 'package.tar.gz').write_bytes(b'bad bytes')
        with self.assertRaises(ValueError):
            self.updater.apply_pending()
        self.assertTrue((self.package / 'old.txt').exists())

    def test_unmanaged_files_remain_available_after_update(self):
        self.stage()
        (self.package / 'personal-note.txt').write_text('preserve this')
        self.updater.apply_pending()
        self.assertEqual((self.package / 'personal-note.txt').read_text(), 'preserve this')

    def test_backend_failure_keeps_old_install(self):
        self.stage()
        self.runtime_validation.side_effect = ValueError('missing runtime dependency')
        with self.assertRaises(ValueError):
            self.updater.apply_pending()
        self.assertTrue((self.package / 'old.txt').exists())
        self.assertFalse(list(self.root.glob('SofaTap-Linux-x64.backup-*')))

    def test_unwritable_install_is_not_mutated(self):
        self.stage()
        with patch.object(self.updater, 'writable', return_value=False):
            self.assertFalse(self.updater.apply_pending())
        self.assertTrue((self.package / 'old.txt').exists())


if __name__ == '__main__':
    unittest.main()
