#!/usr/bin/env python3
"""Test isolated release builds with real git checkouts and fake build tools."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("release.sh").resolve()
GIT = shutil.which("git")


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="twig release ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("tools/release.sh", SCRIPT.read_text())
        self.write("app/build.gradle.kts", 'versionCode = 313\nversionName = "1.16.0"\n')
        self.write(".gitignore", "build/\napp/build/\nbin/\nevents\nlocal.properties\nkeystore.properties\n")
        self.write("local.properties", "debugSign=false\n")
        self.write("keystore.properties", "fixture, not credentials\n")
        for name in ("README.md", "README.zh-CN.md", "THIRD_PARTY.md"):
            self.write(name, "versionCode 313\n")
        for locale in ("en-US", "zh-CN"):
            self.write(f"fastlane/metadata/android/{locale}/changelogs/313.txt", "Release changes\n")
        self.write("gradlew", '''#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys
root = pathlib.Path.cwd()
with open(os.environ['EVENT_LOG'], 'a') as f:
    f.write(json.dumps(dict(cwd=str(root), args=sys.argv[1:],
        commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        signing=(root / 'local.properties').read_text(),
        keystore=(root / 'keystore.properties').read_text(),
        leaked=(root / 'uncommitted.txt').exists())) + '\\n')
if os.environ.get('FAIL_BUILD') == '1': sys.exit(1)
if '-PwithNfs=true' in sys.argv and os.environ.get('FAIL_NFS_BUILD') == '1': sys.exit(1)
if 'clean' in sys.argv:
    import shutil
    shutil.rmtree(root / 'app/build', ignore_errors=True)
for flavor in ('full', 'libre'):
    dest = root / f'app/build/outputs/apk/{flavor}/release'
    dest.mkdir(parents=True, exist_ok=True)
    (dest / f'app-{flavor}-release.apk').write_text(flavor)
    mapping = root / f'app/build/outputs/mapping/{flavor}Release'
    mapping.mkdir(parents=True, exist_ok=True)
    (mapping / 'mapping.txt').write_text('NFS' if '-PwithNfs=true' in sys.argv else flavor)
    if '-PwithNfs=true' in sys.argv and os.environ.get('SKIP_NFS_APK') != '1':
        (dest / f'app-{flavor}-arm64-v8a-release-nfs.apk').write_text('NFS arm64')
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
        (dest / f'app-{flavor}-{abi}-release.apk').write_text(abi)
''', executable=True)
        self.write("tools/verify-apk.py", '''import json, os, sys
with open(os.environ['EVENT_LOG'], 'a') as f:
    f.write('verify-apks:' + json.dumps(sys.argv[1:]) + '\\nverify\\n')
sys.exit(int(os.environ.get('FAIL_VERIFY', '0')))
''')
        self.write("bin/java", "#!/bin/sh\nexit 0\n", executable=True)
        self.write("bin/apksigner", "#!/bin/sh\necho 'Signer #1 certificate SHA-256 digest: fixture'\n", executable=True)
        self.write("bin/git", f'''#!/bin/sh
if [ "$1" = push ]; then echo push >> "$EVENT_LOG"; exit 0; fi
exec '{GIT}' "$@"
''', executable=True)
        self.write("bin/gh", """#!/usr/bin/env python3
import json, os, sys
with open(os.environ['EVENT_LOG'], 'a') as f:
    f.write('github-args:' + json.dumps(sys.argv[1:]) + '\\ngithub\\n')
""", executable=True)
        self.write("bin/curl", '#!/bin/sh\necho 200\n', executable=True)
        self.write("bin/bc", '#!/bin/sh\necho 0.01\n', executable=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "release@example.invalid")
        self.git("config", "user.name", "Release tests")
        self.git("add", ".")
        self.git("commit", "-qm", "release fixture")
        self.commit = self.git("rev-parse", "HEAD")
        self.git("tag", "v1.16.0")
        self.env = dict(os.environ, PATH=f"{self.root / 'bin'}:{os.environ['PATH']}",
                        TWIG_JAVA_HOME=str(self.root), EVENT_LOG=str(self.root / "events"),
                        ANDROID_HOME=str(self.root), TWIG_BUILD_TOOLS="../../bin")

    def write(self, name, content, executable=False):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        if executable:
            path.chmod(0o755)

    def git(self, *args):
        return subprocess.run([GIT, *args], cwd=self.root, check=True,
                              capture_output=True, text=True).stdout.strip()

    def run_script(self, *args, **env):
        return subprocess.run(["bash", "tools/release.sh", *args], cwd=self.root,
                              env=dict(self.env, **env), capture_output=True, text=True)

    def events(self):
        path = self.root / "events"
        return path.read_text().splitlines() if path.exists() else []

    def test_old_tag_rebuild_uses_tag_source_preserves_outputs_and_signing(self):
        self.write("app/build.gradle.kts", 'versionCode = 314\nversionName = "1.16.1"\n')
        self.git("add", "app/build.gradle.kts")
        self.git("commit", "-qm", "later version")
        self.write("uncommitted.txt", "must not reach build")
        old_apk = "build/release/1.16.0/Twig-1.16.0-libre.apk"
        self.write(old_apk, "published APK")
        result = self.run_script("--tag", "v1.16.0", "--offline")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        builds = [json.loads(e) for e in self.events() if e.startswith('{')]
        self.assertEqual(2, len(builds))
        for build in builds:
            self.assertNotEqual(str(self.root), build['cwd'])
            self.assertEqual(self.commit, build['commit'])
            self.assertFalse(build['leaked'])
            self.assertEqual('debugSign=false\n', build['signing'])
            self.assertEqual('fixture, not credentials\n', build['keystore'])
            for flag in ('clean', '--no-build-cache', '--no-configuration-cache',
                         '--rerun-tasks', '-Pkotlin.incremental=false', '--offline'):
                self.assertIn(flag, build['args'])
        self.assertEqual('published APK', (self.root / old_apk).read_text())
        outputs = list((self.root / 'build/release/1.16.0').glob('rebuild.*'))
        self.assertEqual(5, len(list(outputs[0].glob('*.apk'))))
        self.assertIn(self.commit, (outputs[0] / 'source.txt').read_text())
        self.assertEqual('verify', self.events()[-1])
        self.assertEqual('v1.16.0', self.git('tag'))

    def test_default_rehearsal_also_builds_in_fresh_checkout(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(5, len(list((self.root / 'build/release/1.16.0').glob('*.apk'))))
        self.assertNotEqual(str(self.root), json.loads(self.events()[0])['cwd'])
        self.assertEqual('verify', self.events()[-1])

    def enable_nfs(self):
        path = self.root / 'app/build.gradle.kts'
        path.write_text(path.read_text() + 'val withNfs = true\n')
        self.git('add', 'app/build.gradle.kts')
        self.git('commit', '-qm', 'optional NFS support')
        self.git('tag', '-f', 'v1.16.0')

    def test_nfs_rehearsal_builds_one_extra_arm64_apk_with_notes_and_mapping(self):
        self.enable_nfs()
        result = self.run_script('--offline')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        out = self.root / 'build/release/1.16.0'
        nfs = 'Twig-1.16.0-full-nfs-arm64-v8a.apk'
        self.assertEqual(6, len(list(out.glob('*.apk'))))
        self.assertEqual('NFS arm64', (out / nfs).read_text())
        self.assertEqual('NFS', (out / 'mapping/full-nfs-arm64-v8a/mapping.txt').read_text())
        builds = [json.loads(e) for e in self.events() if e.startswith('{')]
        self.assertEqual(3, len(builds))
        for build in builds[:2]:
            self.assertIn('-PwithNfs=false', build['args'])
        for flag in ('clean', '-PwithNfs=true', '-PabiSplit=true', '-Pabis=arm64-v8a', '--offline'):
            self.assertIn(flag, builds[2]['args'])
        self.assertGreaterEqual((out / 'notes.md').read_text().count(nfs), 2)
        self.assertIn(str(out / nfs), json.loads(next(e.removeprefix('verify-apks:')
            for e in self.events() if e.startswith('verify-apks:'))))
        self.assertNotIn('github', self.events())

    def test_nfs_publish_includes_extra_asset_and_mirror(self):
        self.enable_nfs()
        mirror = self.root / 'build/mirror'
        mirror.mkdir(parents=True)
        result = self.run_script('--publish', TWIG_RELEASE_MIRROR=str(mirror))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        args = json.loads(next(e.removeprefix('github-args:') for e in self.events()
                               if e.startswith('github-args:')))
        assets = [a for a in args if '.apk#' in a]
        self.assertEqual(6, len(assets))
        self.assertTrue(any('full-nfs-arm64-v8a.apk#' in a for a in assets))
        self.assertEqual(6, len(list(mirror.glob('*.apk'))))

    def test_nfs_failure_or_missing_apk_never_publishes(self):
        self.enable_nfs()
        for flag in ('FAIL_NFS_BUILD', 'SKIP_NFS_APK'):
            with self.subTest(flag=flag):
                result = self.run_script('--publish', **{flag: '1'})
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn('push', self.events())
                self.assertNotIn('github', self.events())

    def test_rebuild_cannot_publish(self):
        result = self.run_script('--tag', 'v1.16.0', '--publish')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.events())

    def test_dirty_default_and_missing_tag_fail_before_build(self):
        self.write('uncommitted.txt', 'dirty')
        for args in ((), ('--tag', 'v1.15.0')):
            self.assertNotEqual(0, self.run_script(*args).returncode)
        self.assertEqual([], self.events())

    def test_build_or_verification_failure_never_publishes(self):
        for flag in ('FAIL_BUILD', 'FAIL_VERIFY'):
            with self.subTest(flag=flag):
                result = self.run_script('--publish', **{flag: '1'})
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn('push', self.events())
                self.assertNotIn('github', self.events())


if __name__ == '__main__':
    unittest.main()
