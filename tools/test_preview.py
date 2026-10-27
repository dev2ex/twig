#!/usr/bin/env python3
"""Exercise preview publishing with fake builds and GitHub calls; never use the network."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("preview.sh").resolve()
GIT = shutil.which("git")


class PreviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("tools/preview.sh", SCRIPT.read_text(), executable=True)
        self.write("app/build.gradle.kts", 'versionCode = 309\nversionName = "1.16.0-beta.1"\n')
        self.write(".gitignore", "build/\napp/build/\nbin/\nevents\n")
        self.write("keystore.properties", "fixture, not credentials\n")
        for locale in ("en-US", "zh-CN"):
            self.write(f"fastlane/metadata/android/{locale}/changelogs/309.txt", "Preview changes\n")
        self.write("gradlew", '''#!/usr/bin/env bash
set -eu
echo build >> "$EVENT_LOG"
[ "${FAIL_BUILD:-0}" != 1 ] || exit 1
for flavor in full libre; do
  mkdir -p "app/build/outputs/apk/$flavor/release"
  echo "$flavor" > "app/build/outputs/apk/$flavor/release/app-$flavor-release.apk"
done
''', executable=True)
        self.write("tools/verify-apk.py", '''import os, sys
with open(os.environ["EVENT_LOG"], "a") as f: f.write("verify\\n")
sys.exit(int(os.environ.get("FAIL_VERIFY", "0")))
''')
        self.write("bin/java", "#!/bin/sh\nexit 0\n", executable=True)
        self.write("bin/git", f'''#!/bin/sh
if [ "$1" = push ]; then echo push >> "$EVENT_LOG"; exit 0; fi
exec {shlex.quote(GIT)} "$@"
''', executable=True)
        self.write("bin/gh", '''#!/usr/bin/env python3
import json, os, sys
with open(os.environ["EVENT_LOG"], "a") as f: f.write(json.dumps(sys.argv[1:]) + "\\n")
''', executable=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "preview@example.invalid")
        self.git("config", "user.name", "Preview tests")
        self.git("remote", "add", "github", "git@github.com:example/twig.git")
        self.commit()
        self.env = dict(os.environ, PATH=f"{self.root / 'bin'}:{os.environ['PATH']}",
                        TWIG_JAVA_HOME=str(self.root), EVENT_LOG=str(self.root / "events"))

    def write(self, name, content, executable=False):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        if executable:
            path.chmod(0o755)

    def git(self, *args):
        return subprocess.run([GIT, *args], cwd=self.root, check=True, capture_output=True, text=True).stdout.strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

    def run_script(self, *args, **env):
        return subprocess.run(["bash", "tools/preview.sh", *args], cwd=self.root,
                              env=dict(self.env, **env), capture_output=True, text=True)

    def events(self):
        path = self.root / "events"
        return path.read_text().splitlines() if path.exists() else []

    def test_rehearsal_verifies_two_apks_without_creating_tag_or_calling_github(self):
        result = self.run_script("--offline")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["build", "verify"], self.events())
        self.assertEqual("", self.git("tag"))
        notes = (self.root / "build/preview/1.16.0-beta.1/notes.md").read_text()
        self.assertIn("https://github.com/example/twig/blob/v1.16.0-beta.1/LICENSE-EXCEPTIONS.md", notes)
        self.assertIn("### SHA-256", notes)

    def test_publish_is_explicit_prerelease_and_never_latest(self):
        result = self.run_script("--publish")
        self.assertEqual(0, result.returncode, result.stderr)
        events = self.events()
        self.assertLess(events.index("verify"), events.index("push"))
        release = next(json.loads(e) for e in events if e.startswith('["release"'))
        for flag in ("--prerelease", "--latest=false", "--verify-tag"):
            self.assertIn(flag, release)
        self.assertIn("example/twig", release)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.git("rev-parse", "v1.16.0-beta.1^{commit}"))

    def test_build_or_verification_failure_never_pushes_or_tags(self):
        for failure in ("FAIL_BUILD", "FAIL_VERIFY"):
            with self.subTest(failure=failure):
                result = self.run_script("--publish", **{failure: "1"})
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("push", self.events())
                self.assertFalse(any(e.startswith("[") for e in self.events()))
                self.assertEqual("", self.git("tag"))

    def test_dirty_tree_and_stable_version_are_refused_before_build(self):
        self.write("uncommitted.txt", "dirty")
        self.assertNotEqual(0, self.run_script("--publish").returncode)
        self.assertEqual([], self.events())
        (self.root / "uncommitted.txt").unlink()
        self.write("app/build.gradle.kts", 'versionCode = 309\nversionName = "1.16.0"\n')
        self.commit()
        self.assertNotEqual(0, self.run_script("--publish").returncode)
        self.assertEqual([], self.events())


if __name__ == "__main__":
    unittest.main()
