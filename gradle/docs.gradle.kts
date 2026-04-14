// Documentation build & publish tasks (loaded from root build.gradle.kts)
//
// ─── Tasks ──────────────────────────────────────────────────────────────────
// createDocs        — local build: Dokka + Doxygen + MkDocs + static assets → docs/build/
// publishDocs       — deploy stable site to stormify.org root (protects per-version subfolders)
// publishDocsDevel  — deploy as pre-release under stormify.org/docs/<projectVersion>/,
//                     rewrites built HTML so repo links → /tree/devel and backlinks → /docs/<ver>/
// promoteDocs       — server-side ping-pong: archive current stable to /docs/<old>/ with
//                     tree/v<old> rewrites, promote /docs/<new>/ to root with main-branch rewrites.
//                     Run with -PoldVersion=X.Y.Z -PnewVersion=X.Y.Z
// syncReadmeUrls    — rewrite README.md URLs based on current git branch (idempotent):
//                     main → stable defaults; anything else → /docs/<ver>/ + examples tree/<ver>
//
// ─── Versioning strategy ────────────────────────────────────────────────────
// Source of truth for the current version is project.version in build.gradle.kts.
// Source files (README.md, mkdocs.yml, inject-backlink.sh) are kept in stable form.
// Devel/versioned URLs are produced by the tasks above via post-build rewrites.
//
// ─── Workflow ───────────────────────────────────────────────────────────────
// On devel branch (preview publish):
//   gradle syncReadmeUrls       # sync README.md badges to /docs/<ver>/
//   gradle publishDocsDevel     # build + rewrite + rsync to stormify.org/docs/<ver>/
//   git commit -am "..." && git push origin devel
//
// Promoting devel to stable:
//   git checkout main && git merge devel
//   gradle syncReadmeUrls                                             # README → stable defaults
//   gradle promoteDocs -PoldVersion=<prev> -PnewVersion=<projectVer>   # server ping-pong
//   gradle publishDocs                                                 # redeploy landing/static if changed
//
// Notes:
//   - publishDocs protects /docs/[0-9]*/ subfolders from rsync --delete.
//   - promoteDocs refuses to run if /docs/<new>/ is missing or /docs/<old>/ already exists.
//   - syncReadmeUrls is idempotent — safe to run multiple times.

tasks.register("createDocs") {
    group = "documentation"
    description = "Generate documentation site locally (Dokka + Doxygen + MkDocs) into docs/build/"
    dependsOn(
        ":stormify:dokkaGenerateHtml",
        ":kdbc:dokkaGenerateHtml",
        ":logger:dokkaGenerateHtml"
    )
    doLast {
        // 1. Doxygen: KDBC C API reference
        ProcessBuilder("doxygen", "Doxyfile")
            .directory(file("kdbc/src/c"))
            .inheritIO().start().waitFor()
        // 2. MkDocs: main documentation site
        ProcessBuilder("mkdocs", "build")
            .directory(file("docs"))
            .inheritIO().start().waitFor()
        // 3. Doxygen again (mkdocs clean wipes the output dir)
        ProcessBuilder("doxygen", "Doxyfile")
            .directory(file("kdbc/src/c"))
            .inheritIO().start().waitFor()
        // 4. Dokka: copy API docs
        file("stormify/build/dokka/html").copyRecursively(file("docs/build/docs/api-stormify"), overwrite = true)
        file("kdbc/build/dokka/html").copyRecursively(file("docs/build/docs/api-kdbc-kotlin"), overwrite = true)
        // 5. Inject back-link bar into API reference pages
        val inject = file("docs/inject-backlink.sh").absolutePath
        ProcessBuilder("sh", inject, "docs/build/docs/api-stormify").inheritIO().start().waitFor()
        ProcessBuilder("sh", inject, "docs/build/docs/api-kdbc-kotlin").inheritIO().start().waitFor()
        ProcessBuilder("sh", inject, "docs/build/docs/kdbc-c").inheritIO().start().waitFor()
        // 6. Copy static assets
        file("docs/static").copyRecursively(file("docs/build"), overwrite = true)
    }
}

tasks.register("publishDocs") {
    group = "documentation"
    description = "Deploy documentation site to stormify.org (run createDocs first)"
    dependsOn("createDocs")
    // No declared outputs, so force execution on every invocation — otherwise Gradle
    // caches this task as UP-TO-DATE and silently skips the rsync upload.
    outputs.upToDateWhen { false }
    doLast {
        // --exclude protects per-version preview subfolders (docs/2.x/) from --delete
        val exitCode = ProcessBuilder(
            "rsync", "-ravz", "-e", "ssh -p 1971", "--delete",
            "--exclude=docs/[0-9]*",
            "docs/build/", "teras@yot.is:~/web/stormify.org/"
        ).inheritIO().start().waitFor()
        if (exitCode != 0)
            throw GradleException("rsync failed with exit code $exitCode — docs not uploaded")
    }
}

tasks.register("publishDocsDevel") {
    group = "documentation"
    description = "Deploy documentation as pre-release under stormify.org/docs/<projectVersion>/"
    dependsOn("createDocs")
    outputs.upToDateWhen { false }
    doLast {
        val ver = project.version.toString().removeSuffix("-SNAPSHOT")
        if (ver.isBlank() || ver == "unspecified")
            throw GradleException("project.version is not set")
        // Rewrite built HTML/sitemap to point to version-specific URLs (devel branch on GitHub,
        // /docs/<ver>/ for backlink + canonical). Stable defaults in source stay untouched.
        val rewrite = """
            find docs/build/docs -type f \( -name '*.html' -o -name '*.xml' \) -exec sed -i \
              -e 's|href="https://github.com/teras/stormify"|href="https://github.com/teras/stormify/tree/devel"|g' \
              -e 's|https://stormify.org/docs/|https://stormify.org/docs/$ver/|g' \
              -e 's|href="/docs/"|href="/docs/$ver/"|g' \
              {} +
        """.trimIndent()
        val rc = ProcessBuilder("sh", "-c", rewrite).inheritIO().start().waitFor()
        if (rc != 0) throw GradleException("URL rewrite failed (exit $rc)")
        val exitCode = ProcessBuilder(
            "rsync", "-ravz", "-e", "ssh -p 1971", "--delete",
            "docs/build/docs/", "teras@yot.is:~/web/stormify.org/docs/$ver/"
        ).inheritIO().start().waitFor()
        if (exitCode != 0)
            throw GradleException("rsync failed with exit code $exitCode — devel docs not uploaded")
        println("✓ Devel docs deployed: https://stormify.org/docs/$ver/")
    }
}

tasks.register("promoteDocs") {
    group = "documentation"
    description = "Ping-pong: archive current stable under /docs/<old>/ and promote /docs/<new>/ to stable root. " +
        "Run with -PoldVersion=X.Y.Z -PnewVersion=X.Y.Z"
    outputs.upToDateWhen { false }
    doLast {
        val oldVer = project.findProperty("oldVersion") as String?
            ?: throw GradleException("missing -PoldVersion=<current stable version to archive>")
        val newVer = project.findProperty("newVersion") as String?
            ?: throw GradleException("missing -PnewVersion=<version currently living under /docs/<new>/ to promote>")
        val script = """
            set -euo pipefail
            cd ~/web/stormify.org/docs
            if [ ! -d "$newVer" ]; then echo "ERROR: /docs/$newVer/ not found on server" >&2; exit 1; fi
            if [ -e "$oldVer" ]; then echo "ERROR: /docs/$oldVer/ already exists — refusing to overwrite" >&2; exit 1; fi
            # 1. archive current stable: everything except numeric version subfolders
            mkdir "$oldVer"
            for entry in *; do
                case "${'$'}entry" in
                    [0-9]*) ;;
                    "$oldVer") ;;
                    *) mv -- "${'$'}entry" "$oldVer"/ ;;
                esac
            done
            # 2. rewrite archived (old stable) pages: stable URLs → /docs/$oldVer/ + tree/v$oldVer
            find "$oldVer" -type f \( -name '*.html' -o -name '*.xml' \) -exec sed -i \
              -e 's|href="https://github.com/teras/stormify"|href="https://github.com/teras/stormify/tree/v$oldVer"|g' \
              -e 's|https://stormify.org/docs/|https://stormify.org/docs/$oldVer/|g' \
              -e 's|href="/docs/"|href="/docs/$oldVer/"|g' \
              {} +
            # 3. rewrite new-stable pages (currently at /docs/$newVer/): versioned URLs → stable root + main branch
            find "$newVer" -type f \( -name '*.html' -o -name '*.xml' \) -exec sed -i \
              -e 's|href="https://github.com/teras/stormify/tree/devel"|href="https://github.com/teras/stormify"|g' \
              -e 's|https://stormify.org/docs/$newVer/|https://stormify.org/docs/|g' \
              -e 's|href="/docs/$newVer/"|href="/docs/"|g' \
              {} +
            # 4. promote new stable to root
            mv "$newVer"/* "$newVer"/.[!.]* . 2>/dev/null || true
            rmdir "$newVer"
            echo "✓ promoted $newVer to stable; archived previous stable under /docs/$oldVer/"
        """.trimIndent()
        val proc = ProcessBuilder("ssh", "-p", "1971", "teras@yot.is", "bash -s")
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        proc.outputStream.write(script.toByteArray())
        proc.outputStream.close()
        val exit = proc.waitFor()
        if (exit != 0) throw GradleException("Promotion failed (exit $exit)")
    }
}

tasks.register("syncReadmeUrls") {
    group = "documentation"
    description = "Rewrite README.md URLs to match current git branch: stable (/docs/) on main, versioned (/docs/<ver>/) elsewhere. Idempotent."
    outputs.upToDateWhen { false }
    doLast {
        val ver = project.version.toString().removeSuffix("-SNAPSHOT")
        if (ver.isBlank() || ver == "unspecified")
            throw GradleException("project.version is not set")
        val branchProc = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .redirectErrorStream(true).start()
        branchProc.waitFor()
        val branch = branchProc.inputStream.bufferedReader().readText().trim()
        val readme = file("README.md")
        // Step 1 — normalize to stable defaults (strips any previous version-specific rewrites)
        val normalized = readme.readText()
            .replace(Regex("""https://stormify\.org/docs/[0-9][^/"]*/"""), "https://stormify.org/docs/")
            .replace(Regex("""https://github\.com/teras/stormify-examples/tree/[^"\)]+"""), "https://github.com/teras/stormify-examples")
        // Step 2 — if we're not on main, apply devel rewrites using current project.version
        val out = if (branch == "main") normalized else {
            normalized
                .replace("https://stormify.org/docs/", "https://stormify.org/docs/$ver/")
                .replace(Regex("""https://github\.com/teras/stormify-examples(?=["\)])"""), "https://github.com/teras/stormify-examples/tree/$ver")
        }
        if (out != readme.readText()) {
            readme.writeText(out)
            println("✓ README.md synced for branch '$branch' (version $ver)")
        } else {
            println("README.md already in sync for branch '$branch'")
        }
    }
}
