"""After merging upstream, decide the app version of this fork.

Merging takes upstream's app/build.gradle.kts version, which is lower than the fork's own (the fork
has more releases), and an APK with a lower versionCode cannot update an installed one.

usage: keep_fork_version.py <fork sha before the merge> <merge base sha>
- upstream did not change its version since the merge base: the fork's own version is restored.
- upstream raised its version: its versionName is used, and versionCode is made higher than the fork's.
Prints the resulting versionName. Edits app/build.gradle.kts in place; the caller commits it.
"""
import re
import subprocess
import sys

PATH = "app/build.gradle.kts"
CODE = re.compile(r"(versionCode\s*=\s*)(\d+)")
NAME = re.compile(r'(versionName\s*=\s*")([^"]*)(")')


def version(text):
    return int(CODE.search(text).group(2)), NAME.search(text).group(2)


def show(sha):
    return subprocess.run(["git", "show", f"{sha}:{PATH}"], capture_output=True, text=True, check=True).stdout


def main():
    fork_sha, base_sha = sys.argv[1:3]
    text = open(PATH, encoding="utf-8").read()
    fork_code, fork_name = version(show(fork_sha))
    up_code, up_name = version(text)
    _, base_name = version(show(base_sha))
    if up_name == base_name:
        code, name = fork_code, fork_name
    else:
        code, name = max(fork_code, up_code) + (1 if up_code <= fork_code else 0), up_name
    text = CODE.sub(lambda m: m.group(1) + str(code), text, count=1)
    text = NAME.sub(lambda m: m.group(1) + name + m.group(3), text, count=1)
    open(PATH, "w", encoding="utf-8").write(text)
    print(name)


main()
