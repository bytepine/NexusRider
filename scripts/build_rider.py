"""
build_rider.py — NexusRider 插件打包（独立仓）

用法:
    python scripts/build_rider.py --version <版本号> [--output <输出目录>]

说明:
    1. 临时注入 gradle.properties / plugin.xml 版本号
    2. 从 CHANGELOG.md 注入 plugin.xml <change-notes>
    3. gradlew clean buildPlugin
    4. 输出 nexus-mcp-rider-<version>.zip
    5. 恢复源码版本号为 0.0.0
"""

from __future__ import annotations

import argparse
import glob
import html
import os
import re
import shutil
import subprocess
import sys

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if sys.stderr.encoding and sys.stderr.encoding.lower() != "utf-8":
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

_CHANGELOG_HEADING_RE = re.compile(r"^##\s+(.+?)\s*$")


def repo_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_file(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def write_file(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def patch_gradle_properties(props_path: str, version: str) -> str:
    original = read_file(props_path)
    patched = re.sub(
        r"^(pluginVersion\s*=\s*).*$",
        rf"\g<1>{version}",
        original,
        flags=re.MULTILINE,
    )
    write_file(props_path, patched)
    return original


def patch_plugin_xml(xml_path: str, version: str, change_notes_html: str) -> str:
    original = read_file(xml_path)
    patched = re.sub(
        r"(<version>)[^<]*(</version>)",
        lambda _m: f"<version>{version}</version>",
        original,
    )
    block = (
        "<change-notes><![CDATA[\n"
        f"{change_notes_html}\n"
        "  ]]></change-notes>"
    )
    if re.search(r"<change-notes>.*?</change-notes>", patched, flags=re.DOTALL):
        patched = re.sub(
            r"<change-notes>.*?</change-notes>",
            lambda _m: block,
            patched,
            count=1,
            flags=re.DOTALL,
        )
    else:
        patched = re.sub(
            r"(</description>)",
            lambda m: f"{m.group(1)}\n\n    {block}",
            patched,
            count=1,
        )
    write_file(xml_path, patched)
    return original


def parse_changelog_sections(changelog_text: str) -> list:
    sections = []
    current_heading = None
    current_bullets = []
    for line in changelog_text.splitlines():
        m = _CHANGELOG_HEADING_RE.match(line)
        if m:
            if current_heading is not None:
                sections.append((current_heading, current_bullets))
            current_heading = m.group(1).strip()
            current_bullets = []
            continue
        if current_heading is None:
            continue
        stripped = line.strip()
        if stripped.startswith("- "):
            current_bullets.append(stripped[2:].strip())
    if current_heading is not None:
        sections.append((current_heading, current_bullets))
    return sections


def build_change_notes_html(sections: list, current_version: str, max_versions: int = 5) -> str:
    rendered = []
    count = 0
    for heading, bullets in sections:
        if "Unreleased" in heading:
            continue
        if not bullets:
            continue
        if count >= max_versions:
            break
        bullet_html = "\n".join(f"      <li>{html.escape(b)}</li>" for b in bullets)
        rendered.append(
            f"    <h3>{html.escape(heading)}</h3>\n"
            f"    <ul>\n{bullet_html}\n    </ul>"
        )
        count += 1
    if not rendered:
        return (
            f"    <h3>[{html.escape(current_version)}]</h3>\n"
            "    <ul>\n      <li>See CHANGELOG.md for details.</li>\n    </ul>"
        )
    return "\n".join(rendered)


def build_rider_plugin(version: str, output_dir: str) -> str:
    root = repo_root()
    props_path = os.path.join(root, "gradle.properties")
    xml_path = os.path.join(root, "src", "main", "resources", "META-INF", "plugin.xml")
    changelog_path = os.path.join(root, "CHANGELOG.md")
    gradlew = os.path.join(root, "gradlew.bat" if sys.platform == "win32" else "gradlew")

    if not os.path.isfile(gradlew):
        raise FileNotFoundError(f"找不到 gradlew: {gradlew}")

    if os.path.isfile(changelog_path):
        sections = parse_changelog_sections(read_file(changelog_path))
        change_notes_html = build_change_notes_html(sections, version)
    else:
        change_notes_html = build_change_notes_html([], version)

    original_props = patch_gradle_properties(props_path, version)
    original_xml = patch_plugin_xml(xml_path, version, change_notes_html)

    try:
        print(f"[build] gradlew clean buildPlugin (v{version}) ...")
        cmd = [gradlew, "clean", "buildPlugin"]
        result = subprocess.run(cmd, cwd=root, shell=sys.platform == "win32", check=False)
        if result.returncode != 0:
            raise RuntimeError(f"gradlew 返回码: {result.returncode}")

        dist_dir = os.path.join(root, "build", "distributions")
        zips = glob.glob(os.path.join(dist_dir, "*.zip"))
        if not zips:
            raise FileNotFoundError(f"未找到产物: {dist_dir}")

        os.makedirs(output_dir, exist_ok=True)
        dst_path = os.path.join(output_dir, f"nexus-mcp-rider-{version}.zip")
        shutil.copy2(zips[0], dst_path)
        return dst_path
    finally:
        write_file(props_path, original_props)
        write_file(xml_path, original_xml)


def main() -> int:
    parser = argparse.ArgumentParser(description="打包 NexusRider 插件")
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", default=None, help="默认 <repo>/release/")
    args = parser.parse_args()

    root = repo_root()
    output_dir = args.output or os.path.join(root, "release")
    try:
        path = build_rider_plugin(args.version, output_dir)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        return 1
    print(f"[OK] {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
