#!/usr/bin/env bash
# Bump Stormify version across every file that references it.
#
# Usage: scripts/bump-version.sh <new-version>
#
# Updates (idempotent):
#   - build.gradle.kts           (source of truth: version = "X.Y.Z")
#   - README.md                  (install snippets + examples clone + badge version tree/)
#   - docs/src/*.md              (install snippets + examples clone + prose)
#   - examples/ submodule files  (Maven pom + Gradle build files)     [if initialized]
#
# Does NOT touch:
#   - stormify.org/docs/ URLs in README.md — those are driven by branch via syncReadmeUrls
#   - built artifacts (docs/build/)
#   - CLAUDE.md internal references
#
# After running, review the diff, commit, and (inside examples/) commit there too.

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <new-version>" >&2
    exit 1
fi

NEW="$1"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# Detect current version from build.gradle.kts
OLD="$(grep -oE 'version = "[^"]+"' build.gradle.kts | head -1 | sed -E 's/version = "(.*)"/\1/')"
if [[ -z "$OLD" ]]; then
    echo "ERROR: could not detect current version from build.gradle.kts" >&2
    exit 1
fi
if [[ "$OLD" == "$NEW" ]]; then
    echo "Current version is already $NEW — nothing to do."
    exit 0
fi

echo "Bumping $OLD → $NEW"

# Escape for sed BRE
esc() { printf '%s' "$1" | sed 's/[.[\*^$/]/\\&/g'; }
OLD_E="$(esc "$OLD")"

# 1. build.gradle.kts — source of truth
sed -i "s|version = \"$OLD_E\"|version = \"$NEW\"|" build.gradle.kts

# 2. Maven / Gradle dependency coordinates: onl.ycode:<artifact>:<old> → :<new>
# Matches stormify, stormify-jvm, stormify-android, stormify-*native, annproc, logger, kdbc, etc.
replace_deps() {
    local file="$1"
    sed -i -E "s|(onl\.ycode:[a-zA-Z0-9-]+):$OLD_E|\1:$NEW|g" "$file"
    # Maven <version>…</version> preceded by onl.ycode artifactId (loose match — safe
    # because stormify docs never show non-stormify maven deps with version tags)
    sed -i "s|<version>$OLD_E</version>|<version>$NEW</version>|g" "$file"
}

# 3. Examples clone command: git clone -b <old> ... stormify-examples.git
replace_clone() {
    sed -i -E "s|(git clone -b )$OLD_E( https://github\\.com/teras/stormify-examples\\.git)|\1$NEW\2|g" "$1"
}

# 4. stormify-examples/tree/<old> (only outside URL-rewriting managed by syncReadmeUrls — but
#    safe to re-apply in source; syncReadmeUrls will normalize again based on branch)
replace_tree_ref() {
    sed -i -E "s|(github\\.com/teras/stormify-examples/tree/)$OLD_E|\1$NEW|g" "$1"
}

# 5. Prose in Examples.md: "Every example targets Stormify `<old>`"
replace_prose() {
    sed -i -E "s|(targets Stormify \`)$OLD_E(\`)|\1$NEW\2|g" "$1"
}

# Apply to docs + README
CORE_DOCS=(
    README.md
    docs/src/README.md
    docs/src/Annotations.md
    docs/src/Migration_V1_to_V2.md
    docs/src/PagedList.md
    docs/src/Examples.md
)

for f in "${CORE_DOCS[@]}"; do
    if [[ -f "$f" ]]; then
        replace_deps "$f"
        replace_clone "$f"
        replace_tree_ref "$f"
        replace_prose "$f"
    fi
done

# 6. Submodule examples/ — only if initialized
#
# Examples use property-based versions so they build both standalone (picking
# up this default) and inside this repo (overridden via -PstormifyVersion /
# -Dstormify.version). We update the defaults here:
#
#   - Gradle: val stormifyVersion … .getOrElse("X.Y.Z")
#   - Maven:  <stormify.version>X.Y.Z</stormify.version>
#
# Any remaining hardcoded onl.ycode:*:<old> coordinates (e.g. in docs or
# legacy examples not yet migrated to property-based) are also updated.
if [[ -d examples/.git || -f examples/.git ]]; then
    echo "Also updating examples/ submodule…"
    find examples -type f -name "*.gradle.kts" \
        ! -path "*/build/*" ! -path "*/.gradle/*" \
        -exec sed -i -E "s|(getOrElse\\(\")$OLD_E(\"\\))|\\1$NEW\\2|g" {} +
    find examples -type f \( -name "*.gradle.kts" -o -name "pom.xml" \) \
        ! -path "*/build/*" ! -path "*/.gradle/*" \
        -exec sed -i -E "s|(onl\\.ycode:[a-zA-Z0-9-]+):$OLD_E|\\1:$NEW|g" {} +
    find examples -type f -name "pom.xml" ! -path "*/build/*" \
        -exec sed -i -E "s|(<stormify\\.version>)$OLD_E(</stormify\\.version>)|\\1$NEW\\2|g" {} +
else
    echo "Note: examples/ submodule is not initialized — skipped."
fi

echo
echo "Done. Review with:  git diff"
echo "Then commit core repo and (cd examples && git commit ...) for the submodule."
echo
echo "Remember to run afterwards:"
echo "  gradle syncReadmeUrls    # sync README URL subpaths to the new version / current branch"
