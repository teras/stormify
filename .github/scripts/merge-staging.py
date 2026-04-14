#!/usr/bin/env python3
"""Merge two Maven staging trees produced by parallel CI builds.

For SNAPSHOT publications, each build host stamps its own timestamp into the
filename (`stormify-2.0.1-20260414.112416-1.jar` vs `...-20260414.112417-1.jar`),
so the two stagings never have matching paths. We:

1. For every SNAPSHOT directory present in either staging, identify the
   timestamped versions in each and pick a single canonical timestamp (the
   latest seen across both hosts).
2. Rename non-canonical files to the canonical timestamp in both stagings.
3. Copy macOS first (its metadata jar is richer), then Linux (skip-existing).
4. For root *.module files (the "facade" KMP module): take the variants from
   BOTH hosts, dedupe by name, and write a single merged file under the
   canonical timestamp.
5. Regenerate `maven-metadata.xml` to list only the canonical timestamp.
6. Touch all rewritten files so the caller's signing step picks them up.

Per-target Maven coordinates (stormify-jvm, stormify-macosarm64, ...) don't
overlap between the two hosts, so they just get copied as-is.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

# Snapshot suffix in filenames: -YYYYMMDD.HHMMSS-N
SNAPSHOT_TS = re.compile(r"-(\d{8}\.\d{6}-\d+)")


def list_snapshot_dirs(root: Path) -> set[Path]:
    """Find every directory that is a Maven SNAPSHOT version dir, relative to root."""
    out: set[Path] = set()
    for d in root.rglob("*-SNAPSHOT"):
        if d.is_dir():
            out.add(d.relative_to(root))
    return out


def timestamps_in(d: Path) -> set[str]:
    """Extract the unique snapshot timestamp tokens (`YYYYMMDD.HHMMSS-N`) from filenames in a dir."""
    stamps: set[str] = set()
    if not d.exists():
        return stamps
    for f in d.iterdir():
        m = SNAPSHOT_TS.search(f.name)
        if m:
            stamps.add(m.group(1))
    return stamps


def rename_timestamps(d: Path, old: str, new: str) -> None:
    """Rename every file in d whose name contains `-old` so it uses `-new` instead."""
    if old == new or not d.exists():
        return
    for f in list(d.iterdir()):
        if f.is_file() and f"-{old}" in f.name:
            f.rename(f.parent / f.name.replace(f"-{old}", f"-{new}"))


def find_root_module(d: Path, ts: str) -> Path | None:
    """The root KMP .module file is named after the artifactId (parent of version dir).

    Example: in `.../stormify/2.0.1-SNAPSHOT/`, the root module is
    `stormify-2.0.1-{ts}.module`. Per-target coordinates (`stormify-jvm`) live
    in their own version dirs, so they don't conflict.
    """
    if not d.exists():
        return None
    artifact_id = d.parent.name  # e.g. "stormify"
    candidate = d / f"{artifact_id}-2.0.1-{ts}.module"
    return candidate if candidate.exists() else None


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


def regenerate_maven_metadata(dest: Path, canonical_ts: str) -> None:
    """Rewrite maven-metadata.xml to list only the canonical timestamp."""
    md = dest / "maven-metadata.xml"
    if not md.exists():
        return
    tree = ET.parse(md)
    root = tree.getroot()
    versioning = root.find("versioning")
    if versioning is None:
        return
    # Update <snapshot>
    snap = versioning.find("snapshot")
    if snap is not None:
        ts_part, _, build_part = canonical_ts.rpartition("-")
        snap.find("timestamp").text = ts_part  # type: ignore[union-attr]
        snap.find("buildNumber").text = build_part  # type: ignore[union-attr]
    # Update <snapshotVersions>: rewrite every <value> to canonical
    sv = versioning.find("snapshotVersions")
    if sv is not None:
        for entry in sv.findall("snapshotVersion"):
            v = entry.find("value")
            if v is not None and v.text:
                # Replace the timestamp portion of the version string
                v.text = SNAPSHOT_TS.sub(f"-{canonical_ts}", v.text)
    tree.write(md, encoding="UTF-8", xml_declaration=True)


def merge_snapshot_dir(linux_dir: Path, macos_dir: Path, merged_dir: Path) -> int:
    """Returns count of root .module files that were merged."""
    linux_stamps = timestamps_in(linux_dir)
    macos_stamps = timestamps_in(macos_dir)
    all_stamps = linux_stamps | macos_stamps
    if not all_stamps:
        # Plain copy (no timestamped files — e.g. annproc which isn't KMP)
        return 0
    canonical = max(all_stamps)  # lexicographic == chronological for our format

    # Normalize timestamps in both source dirs IN-PLACE on local copies first.
    # We do this on the merged_dir AFTER copy to avoid mutating downloaded artifacts.

    merged_count = 0
    artifact_id = linux_dir.parent.name if linux_dir.exists() else macos_dir.parent.name

    # Look for root .module files in both sides under their original timestamps
    linux_module: Path | None = None
    macos_module: Path | None = None
    for ts in linux_stamps:
        cand = linux_dir / f"{artifact_id}-2.0.1-{ts}.module"
        if cand.exists():
            linux_module = cand
            break
    for ts in macos_stamps:
        cand = macos_dir / f"{artifact_id}-2.0.1-{ts}.module"
        if cand.exists():
            macos_module = cand
            break

    # If both produced root modules, merge into canonical name
    if linux_module and macos_module:
        out = merged_dir / f"{artifact_id}-2.0.1-{canonical}.module"
        merge_module(linux_module, macos_module, out)
        out.touch()
        merged_count = 1
        # Remove the non-canonical stale module + its sigs/checksums from merged
        for ts in all_stamps:
            if ts == canonical:
                continue
            stale_base = merged_dir / f"{artifact_id}-2.0.1-{ts}.module"
            for ext in ("", ".asc", ".md5", ".sha1", ".sha256", ".sha512"):
                p = stale_base.with_name(stale_base.name + ext) if ext else stale_base
                if p.exists():
                    p.unlink()

    # Rename ALL non-canonical timestamped files in merged dir to canonical timestamp
    for ts in all_stamps:
        if ts == canonical:
            continue
        rename_timestamps(merged_dir, ts, canonical)

    # Update maven-metadata.xml to point at canonical
    regenerate_maven_metadata(merged_dir, canonical)
    md = merged_dir / "maven-metadata.xml"
    if md.exists():
        md.touch()
        # Drop checksums — caller doesn't re-checksum maven-metadata. Recompute.
        for ext in (".md5", ".sha1", ".sha256", ".sha512"):
            (md.parent / f"maven-metadata.xml{ext}").unlink(missing_ok=True)
        import hashlib
        data = md.read_bytes()
        for algo, ext in [("md5", ".md5"), ("sha1", ".sha1"), ("sha256", ".sha256"), ("sha512", ".sha512")]:
            (md.parent / f"maven-metadata.xml{ext}").write_text(hashlib.new(algo, data).hexdigest())

    return merged_count


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

    print(f"Copying {macos_root} -> {merged_root}")
    copy_tree(macos_root, merged_root)
    print(f"Copying {linux_root} -> {merged_root} (skip existing)")
    copy_tree(linux_root, merged_root, skip_existing=True)

    snapshot_dirs = list_snapshot_dirs(linux_root) | list_snapshot_dirs(macos_root)
    print(f"\nProcessing {len(snapshot_dirs)} SNAPSHOT directories:")
    total_merged = 0
    for rel in sorted(snapshot_dirs):
        ldir = linux_root / rel
        mdir = macos_root / rel
        merged_dir = merged_root / rel
        merged_count = merge_snapshot_dir(ldir, mdir, merged_dir)
        marker = " [merged root .module]" if merged_count else ""
        print(f"  {rel}{marker}")
        total_merged += merged_count

    print(f"\nDone. {total_merged} root modules merged. Output: {merged_root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
