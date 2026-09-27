"""Generate release notes from commits reachable from the selected revision."""

import os
from pathlib import Path
import re
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


tag = os.environ["RELEASE_TAG"]
if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
    raise ValueError("RELEASE_TAG must be a stable vX.Y.Z version")
version = tuple(map(int, tag[1:].split(".")))
repository = os.environ["GITHUB_REPOSITORY"]
base_url = f"https://github.com/{repository}"
# Only consider stable release tags contained in the selected branch.
tags = git("tag", "--merged", "HEAD", "--sort=-v:refname").splitlines()
previous = next(
    (item for item in tags if re.fullmatch(r"v\d+\.\d+\.\d+", item)
     and tuple(map(int, item[1:].split("."))) < version),
    None,
)
revision = f"{previous}..HEAD" if previous else "HEAD"
commits = git("log", "--no-merges", "--reverse", "--format=%H%x09%s", revision)
groups = {title: [] for title in ("功能", "修复", "性能与优化", "文档", "其他更新")}
categories = {
    "feat": "功能", "fix": "修复", "perf": "性能与优化",
    "refactor": "性能与优化", "docs": "文档",
}
for line in commits.splitlines():
    sha, subject = line.split("\t", 1)
    match = re.match(r"(\w+)(?:\([^)]+\))?(!)?:\s*(.+)", subject)
    category = categories.get(match[1], "其他更新") if match else "其他更新"
    description = match[3] if match else subject
    if match and match[2]:
        description = f"**不兼容变更：** {description}"
    groups[category].append(f"- {description} ([{sha[:7]}]({base_url}/commit/{sha}))")

sections = []
notes = os.environ.get("RELEASE_NOTES", "").strip()
if notes:
    sections.append(f"## 升级说明\n\n{notes}")
for title, entries in groups.items():
    if entries:
        sections.append(f"## {title}\n\n" + "\n".join(entries))
if not any(groups.values()):
    sections.append("常规更新与优化")
if previous:
    sections.append(f"[完整变更]({base_url}/compare/{previous}...{tag})")
Path("changelog.txt").write_text("\n\n".join(sections) + "\n", encoding="utf-8")
