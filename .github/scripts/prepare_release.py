"""Prepare a local version commit and tag. The release job pushes them after signing."""

import os
import re
import subprocess
from pathlib import Path


VERSION_FILE = Path("version.properties")


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def prepare(branch):
    fields = dict(line.split("=", 1) for line in VERSION_FILE.read_text(encoding="utf-8").splitlines() if line)
    version = fields["versionName"]
    code = int(fields["versionCode"])
    if not re.fullmatch(r"0\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version) or not 0 < code <= 2_100_000_000:
        raise SystemExit("Invalid Android release version in version.properties")

    source = git("rev-parse", "HEAD")
    tag = f"v{version}"
    if git("tag", "--points-at", source, "--list", tag) == tag:
        return version, code, False

    series, patch = version.rsplit(".", 1)
    version = f"{series}.{int(patch) + 1}"
    code += 1
    if code > 2_100_000_000:
        raise SystemExit("Android versionCode limit reached")
    tag = f"v{version}"
    contents = f"versionName={version}\nversionCode={code}\n"
    branch_advanced = source != git("rev-parse", f"refs/remotes/origin/{branch}")
    superseded_push = branch_advanced and os.environ["GITHUB_EVENT_NAME"] == "push"

    if git("tag", "--list", tag) == tag:
        # A previous attempt may have pushed the version commit before uploading all assets.
        commit = git("rev-parse", f"refs/tags/{tag}^{{commit}}")
        parents = git("rev-list", "--parents", "-1", commit).split()[1:]
        if (parents == [source] and git("diff", "--name-only", source, commit) == VERSION_FILE.name
                and git("show", f"{commit}:{VERSION_FILE.name}") == contents.strip()):
            git("checkout", "--quiet", "--detach", commit)
            return version, code, False
        if not superseded_push:
            raise SystemExit(f"{tag} already belongs to a different update")

    if superseded_push:
        print("Skipping a superseded push to the default branch.")
        return None
    if branch_advanced:
        raise SystemExit("The default branch advanced. Start Release from its latest commit.")
    VERSION_FILE.write_text(contents, encoding="utf-8")
    git("-c", "user.name=github-actions[bot]", "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "commit", "--only", "-m", f"chore(release): {tag}", "--", VERSION_FILE.name)
    git("tag", tag)
    return version, code, True


if __name__ == "__main__":
    release = prepare(os.environ["GITHUB_REF_NAME"])
    if release is not None:
        version, code, new = release
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            print(f"version={version}\ncode={code}\nnew={'true' if new else 'false'}", file=output)
