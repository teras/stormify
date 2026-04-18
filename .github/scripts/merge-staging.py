#!/usr/bin/env python3
"""Merge two Maven staging trees produced by parallel CI builds.

Each build host (Linux, macOS) produces its own staging tree. Per-target
Maven coordinates (stormify-jvm, stormify-macosarm64, ...) are disjoint
across hosts, so they just get copied as-is. The ONE conflicting artifact
is the root KMP facade module (`stormify-<version>.module` etc.) — each
host writes one that only declares its own target variants. We take the
union of the `variants` array from both.

Two cases:
  - SNAPSHOT: filenames carry `-YYYYMMDD.HHMMSS-N`. The two hosts have
    different timestamps, so we pick the latest as canonical, rename
    everything in the merged tree to it, and rewrite `maven-metadata.xml`.
  - RELEASE: no timestamp suffix. Filenames match across hosts, so we just
    merge the root .module file in place.

The caller is expected to re-sign/re-checksum the merged root .module
files (per-target artifacts keep their original signatures since their
bytes never changed).
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

# Snapshot suffix in filenames: -YYYYMMDD.HHMMSS-N
SNAPSHOT_TS = re.compile(r"-(\d{8}\.\d{6}-\d+)")

# Root module filename: <artifactId>-<version>.module
# Version is anything up to the final `.module`; on SNAPSHOT it includes
# `-SNAPSHOT` OR the resolved timestamp form `-YYYYMMDD.HHMMSS-N`.
ROOT_MODULE_RE = re.compile(r"^(?P<artifact>[^/]+?)-(?P<version>.+)\.module$")


def list_version_dirs(root: Path) -> set[Path]:
    """Find every Maven version directory (snapshot or release) under root.

    A version dir is one whose parent.name matches the artifactId and which
    directly contains a `<artifactId>-*.module` or `.pom` file. We detect
    them by looking for any `*.pom` file (every published GAV has one).
    """
    out: set[Path] = set()
    for pom in root.rglob("*.pom"):
        out.add(pom.parent.relative_to(root))
    return out


def timestamps_in(d: Path) -> set[str]:
    stamps: set[str] = set()
    if not d.exists():
        return stamps
    for f in d.iterdir():
        m = SNAPSHOT_TS.search(f.name)
        if m:
            stamps.add(m.group(1))
    return stamps


def rename_timestamps(d: Path, old: str, new: str) -> None:
    if old == new or not d.exists():
        return
    for f in list(d.iterdir()):
        if f.is_file() and f"-{old}" in f.name:
            f.rename(f.parent / f.name.replace(f"-{old}", f"-{new}"))


def find_root_module(d: Path, artifact_id: str) -> Path | None:
    """Locate the root KMP facade module file in a version dir, if present.

    Matches `<artifactId>-<version>.module` but excludes per-target modules
    like `<artifactId>-jvm-<version>.module` (those live in different dirs
    because per-target coords have a different artifactId).
    """
    if not d.exists():
        return None
    for f in d.iterdir():
        m = ROOT_MODULE_RE.match(f.name)
        if m and m.group("artifact") == artifact_id:
            return f
    return None


def merge_module(linux_module: Path, macos_module: Path, out: Path) -> None:
    with linux_module.open() as f:
        linux = json.load(f)
    with macos_module.open() as f:
        macos = json.load(f)

    by_name: dict[str, dict] = {}
    for v in linux.get("variants", []):
        by_name[v["name"]] = v
    for v in macos.get("variants", []):
        by_name[v["name"]] = v

    merged = dict(macos)  # macOS base — has appleMain-aware metadata
    merged["variants"] = list(by_name.values())

    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as f:
        json.dump(merged, f, indent=2)


def rewrite_checksums(f: Path) -> None:
    """(Re)write .md5/.sha1/.sha256/.sha512 sidecar files for f."""
    data = f.read_bytes()
    for algo, ext in [("md5", ".md5"), ("sha1", ".sha1"),
                      ("sha256", ".sha256"), ("sha512", ".sha512")]:
        (f.parent / f"{f.name}{ext}").write_text(hashlib.new(algo, data).hexdigest())


def regenerate_maven_metadata(dest: Path, canonical_ts: str) -> None:
    md = dest / "maven-metadata.xml"
    if not md.exists():
        return
    tree = ET.parse(md)
    root = tree.getroot()
    versioning = root.find("versioning")
    if versioning is None:
        return
    snap = versioning.find("snapshot")
    if snap is not None:
        ts_part, _, build_part = canonical_ts.rpartition("-")
        ts_el = snap.find("timestamp")
        bn_el = snap.find("buildNumber")
        if ts_el is not None:
            ts_el.text = ts_part
        if bn_el is not None:
            bn_el.text = build_part
    sv = versioning.find("snapshotVersions")
    if sv is not None:
        for entry in sv.findall("snapshotVersion"):
            v = entry.find("value")
            if v is not None and v.text:
                v.text = SNAPSHOT_TS.sub(f"-{canonical_ts}", v.text)
    tree.write(md, encoding="UTF-8", xml_declaration=True)


def merge_version_dir(linux_dir: Path, macos_dir: Path, merged_dir: Path) -> int:
    """Merge a single version directory. Returns 1 if a root .module was merged."""
    # artifactId is the version dir's parent directory name
    ref = linux_dir if linux_dir.exists() else macos_dir
    artifact_id = ref.parent.name

    linux_stamps = timestamps_in(linux_dir)
    macos_stamps = timestamps_in(macos_dir)
    is_snapshot = bool(linux_stamps or macos_stamps)

    linux_module = find_root_module(linux_dir, artifact_id)
    macos_module = find_root_module(macos_dir, artifact_id)

    if is_snapshot:
        canonical = max(linux_stamps | macos_stamps)
        # Rename non-canonical timestamps in the merged output to the canonical one
        for ts in linux_stamps | macos_stamps:
            if ts != canonical:
                rename_timestamps(merged_dir, ts, canonical)
        # Update maven-metadata.xml + its checksums
        md = merged_dir / "maven-metadata.xml"
        if md.exists():
            regenerate_maven_metadata(merged_dir, canonical)
            for ext in (".md5", ".sha1", ".sha256", ".sha512"):
                (md.parent / f"maven-metadata.xml{ext}").unlink(missing_ok=True)
            rewrite_checksums(md)

        # Resolve the canonical merged .module path
        if linux_module and macos_module:
            # Both modules exist — must be identified by name after rename,
            # but before rename they lived at their own timestamp. Locate the
            # merged-output module (renamed above) and overwrite with the union.
            out = find_root_module(merged_dir, artifact_id)
            if out is None:
                # Shouldn't happen: at least one of the inputs was copied
                return 0
            merge_module(linux_module, macos_module, out)
            # Drop stale signature/checksum; caller will re-sign + re-checksum
            for ext in (".asc", ".md5", ".sha1", ".sha256", ".sha512"):
                (out.parent / f"{out.name}{ext}").unlink(missing_ok=True)
            return 1
        return 0

    # RELEASE path — identical filenames, just merge in place
    if linux_module and macos_module:
        out = merged_dir / linux_module.name
        merge_module(linux_module, macos_module, out)
        for ext in (".asc", ".md5", ".sha1", ".sha256", ".sha512"):
            (out.parent / f"{out.name}{ext}").unlink(missing_ok=True)
        return 1
    return 0


def copy_tree(src: Path, dst: Path, skip_existing: bool = False) -> None:
    for root, _dirs, files in os.walk(src):
        rel = Path(root).relative_to(src)
        target_dir = dst / rel
        target_dir.mkdir(parents=True, exist_ok=True)
        for f in files:
            s = Path(root) / f
            d = target_dir / f
            if skip_existing and d.exists():
                continue
            shutil.copy2(s, d)


def main() -> int:
    if len(sys.argv) != 4:
        print(f"usage: {sys.argv[0]} <linux-staging> <macos-staging> <merged-out>", file=sys.stderr)
        return 2

    linux_root = Path(sys.argv[1]).resolve()
    macos_root = Path(sys.argv[2]).resolve()
    merged_root = Path(sys.argv[3]).resolve()

    if merged_root.exists():
        shutil.rmtree(merged_root)
    merged_root.mkdir(parents=True)

    # macOS first (its root .module has appleMain-aware metadata we prefer as base),
    # then Linux filling in anything missing (skip-existing keeps macOS copies).
    print(f"Copying {macos_root} -> {merged_root}")
    copy_tree(macos_root, merged_root)
    print(f"Copying {linux_root} -> {merged_root} (skip existing)")
    copy_tree(linux_root, merged_root, skip_existing=True)

    version_dirs = list_version_dirs(linux_root) | list_version_dirs(macos_root)
    print(f"\nProcessing {len(version_dirs)} version directories:")
    total_merged = 0
    for rel in sorted(version_dirs):
        merged_count = merge_version_dir(
            linux_root / rel, macos_root / rel, merged_root / rel
        )
        marker = " [merged root .module]" if merged_count else ""
        print(f"  {rel}{marker}")
        total_merged += merged_count

    print(f"\nDone. {total_merged} root modules merged. Output: {merged_root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
