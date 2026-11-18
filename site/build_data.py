"""Collects the releases of every app in apps.json into data.json, which index.html reads.

usage: python3 site/build_data.py   (needs the gh CLI and GH_TOKEN)
Runs in the "Site" workflow, so the page never calls GitHub's API from a visitor's browser.
"""
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).parent


def gh(path):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True)
    if out.returncode != 0:
        print(out.stderr, file=sys.stderr)
        raise SystemExit(f"gh api {path} failed")
    return json.loads(out.stdout)


def asset(a):
    return {"name": a["name"], "size": a["size"], "url": a["browser_download_url"], "downloads": a["download_count"]}


def main():
    apps = json.loads((HERE / "apps.json").read_text(encoding="utf-8"))
    for app in apps:
        releases = [r for r in gh(f"repos/{app['repo']}/releases?per_page=30") if not r["draft"]]
        windows_tag = app.get("windows_tag")
        stable = [r for r in releases if not r["prerelease"]]
        app["releases"] = [
            {
                "tag": r["tag_name"],
                "name": r["name"],
                "date": r["published_at"],
                "url": r["html_url"],
                "assets": [asset(a) for a in r["assets"]],
            }
            for r in stable
        ]
        windows = next((r for r in releases if r["tag_name"] == windows_tag), None)
        app["windows"] = (
            {"date": windows["published_at"], "assets": [asset(a) for a in windows["assets"] if a["name"].endswith(".zip")]}
            if windows
            else None
        )
        app["repo_url"] = f"https://github.com/{app['repo']}"
    data = {"generated": datetime.now(timezone.utc).isoformat(timespec="seconds"), "apps": apps}
    (HERE / "data.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print("apps:", len(apps), "releases:", sum(len(a["releases"]) for a in apps))


main()
