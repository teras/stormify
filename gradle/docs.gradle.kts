// Documentation build & publish tasks (loaded from root build.gradle.kts)
//
// ─── Model ──────────────────────────────────────────────────────────────────
// Every version lives permanently at stormify.org/docs/<ver>/. There is no stable
// /docs/ root and no archiving. The landing page at stormify.org/ points to whichever
// version is currently "released" via a {{DOCS_VERSION}} placeholder in docs/static/.
//
// Folders under /docs/ come in two flavours:
//   • preview  — carries a .devel-marker sentinel; overwritten by every publish,
//                only one exists at a time.
//   • released — marker stripped; immutable, kept forever.
//
// ─── Tasks ──────────────────────────────────────────────────────────────────
// createDocs     — local build: Dokka + Doxygen + MkDocs + static assets → docs/build/
// publishDocs    — deploy /docs/<projectVersion>/ as preview. Automatically removes
//                  any other preview folder (any /docs/<X>/ with a .devel-marker).
//                  Released folders have no marker and are never touched.
// releaseDocs    — strip .devel-marker from /docs/<projectVersion>/ (makes it immutable)
//                  and update the landing page at stormify.org/ to point to it.
// syncReadmeUrls — rewrite README.md URLs to reference /docs/<projectVersion>/ and
//                  stormify-examples/tree/<projectVersion>. Idempotent.
//
// ─── Workflow ───────────────────────────────────────────────────────────────
// Publish a preview:
//   gradle publishDocs           # build + deploy /docs/<ver>/ (replaces previous preview)
//
// Release:
//   gradle releaseDocs           # strips marker + updates landing → /docs/<ver>/
//   gradle syncReadmeUrls        # rewrites README.md links to /docs/<ver>/
//   git commit -am "release <ver>" && git push

// Docs outputs live under docs/build/ (not under the root project build/), so
// the default `clean` task never touches them. Hook an explicit cleanup in.
val cleanDocs = tasks.register<Delete>("cleanDocs") {
    group = "documentation"
    description = "Delete docs/build/ and docs/build-landing/ (hooks into `clean`)"
    delete(file("docs/build"), file("docs/build-landing"))
}
tasks.named("clean") {
    dependsOn(cleanDocs)
}

tasks.register("createDocs") {
    group = "documentation"
    description = "Generate documentation site locally (Dokka + Doxygen + MkDocs) into docs/build/"
    dependsOn(
        ":stormify:dokkaGenerateHtml",
        ":kdbc:dokkaGenerateHtml",
        ":logger:dokkaGenerateHtml"
    )
    doLast {
        val ver = project.version.toString().removeSuffix("-SNAPSHOT")
        if (ver.isBlank() || ver == "unspecified")
            throw GradleException("project.version is not set")
        // 1. Doxygen: KDBC C API reference
        ProcessBuilder("doxygen", "Doxyfile")
            .directory(file("kdbc/src/c"))
            .inheritIO().start().waitFor()
        // 2. MkDocs: main documentation site (site_name picked up from env)
        val mkdocs = ProcessBuilder("mkdocs", "build")
            .directory(file("docs"))
            .inheritIO()
        mkdocs.environment()["STORMIFY_VERSION_NAME"] = "Stormify $ver"
        mkdocs.start().waitFor()
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
        // 6. Copy static assets with {{DOCS_VERSION}} substituted to current version
        //    (so local preview under docs/build/ works end-to-end)
        file("docs/static").copyRecursively(file("docs/build"), overwrite = true)
        file("docs/build").walkTopDown()
            .filter { it.isFile && (it.extension == "html" || it.extension == "htm") }
            .forEach { f ->
                val content = f.readText()
                if (content.contains("{{DOCS_VERSION}}"))
                    f.writeText(content.replace("{{DOCS_VERSION}}", ver))
            }
    }
}

tasks.register("publishDocs") {
    group = "documentation"
    description = "Deploy documentation as preview under stormify.org/docs/<projectVersion>/ (replaces previous preview)"
    dependsOn("createDocs")
    outputs.upToDateWhen { false }
    doLast {
        val ver = project.version.toString().removeSuffix("-SNAPSHOT")
        if (ver.isBlank() || ver == "unspecified")
            throw GradleException("project.version is not set")
        // Rewrite built HTML/sitemap so in-page links point to /docs/<ver>/ (versioned)
        // and GitHub links point to the devel branch.
        val rewrite = """
            find docs/build/docs -type f \( -name '*.html' -o -name '*.xml' \) -exec sed -i \
              -e 's|href="https://github.com/teras/stormify"|href="https://github.com/teras/stormify/tree/devel"|g' \
              -e 's|https://stormify.org/docs/|https://stormify.org/docs/$ver/|g' \
              -e 's|href="/docs/"|href="/docs/$ver/"|g' \
              {} +
        """.trimIndent()
        val rc = ProcessBuilder("sh", "-c", rewrite).inheritIO().start().waitFor()
        if (rc != 0) throw GradleException("URL rewrite failed (exit $rc)")
        // Remove any previous preview folder on the server (any /docs/<X>/ that carries
        // .devel-marker and is not the current version). Released folders have no marker
        // and are left alone.
        val cleanup = """
            set -euo pipefail
            cd ~/web/stormify.org/docs 2>/dev/null || exit 0
            for d in */; do
                d="${'$'}{d%/}"
                if [ -f "${'$'}d/.devel-marker" ] && [ "${'$'}d" != "$ver" ]; then
                    echo "Removing stale preview: /docs/${'$'}d/"
                    rm -rf -- "${'$'}d"
                fi
            done
        """.trimIndent()
        val cleanupRc = ProcessBuilder("ssh", "-p", "1971", "teras@yot.is", "bash -s")
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start().apply {
                outputStream.write(cleanup.toByteArray())
                outputStream.close()
            }.waitFor()
        if (cleanupRc != 0) throw GradleException("Preview cleanup failed (exit $cleanupRc)")
        // Rsync new preview
        val exitCode = ProcessBuilder(
            "rsync", "-ravz", "-e", "ssh -p 1971", "--delete",
            "docs/build/docs/", "teras@yot.is:~/web/stormify.org/docs/$ver/"
        ).inheritIO().start().waitFor()
        if (exitCode != 0)
            throw GradleException("rsync failed with exit code $exitCode — preview docs not uploaded")
        // Tag as preview
        val markRc = ProcessBuilder("ssh", "-p", "1971", "teras@yot.is",
            "touch ~/web/stormify.org/docs/$ver/.devel-marker").inheritIO().start().waitFor()
        if (markRc != 0) throw GradleException("Failed to tag preview with .devel-marker (exit $markRc)")
        println("✓ Preview deployed: https://stormify.org/docs/$ver/")
    }
}

tasks.register("releaseDocs") {
    group = "documentation"
    description = "Promote /docs/<projectVersion>/ from preview to released (strip marker) and update the landing page to point to it"
    outputs.upToDateWhen { false }
    doLast {
        val ver = project.version.toString().removeSuffix("-SNAPSHOT")
        if (ver.isBlank() || ver == "unspecified")
            throw GradleException("project.version is not set")
        // 1. Server-side: validate preview exists and strip the marker
        val remote = """
            set -euo pipefail
            cd ~/web/stormify.org/docs
            if [ ! -d "$ver" ]; then
                echo "ERROR: /docs/$ver/ not found on server — publish it first" >&2; exit 1
            fi
            if [ ! -f "$ver/.devel-marker" ]; then
                echo "ERROR: /docs/$ver/ has no .devel-marker — already released?" >&2; exit 1
            fi
            rm -f "$ver/.devel-marker"
            echo "✓ Released /docs/$ver/ (marker removed)"
        """.trimIndent()
        val remoteRc = ProcessBuilder("ssh", "-p", "1971", "teras@yot.is", "bash -s")
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start().apply {
                outputStream.write(remote.toByteArray())
                outputStream.close()
            }.waitFor()
        if (remoteRc != 0) throw GradleException("Server-side release step failed (exit $remoteRc)")
        // 2. Build landing bundle locally with placeholder substituted
        val landingDir = file("docs/build-landing")
        landingDir.deleteRecursively()
        file("docs/static").copyRecursively(landingDir)
        landingDir.walkTopDown()
            .filter { it.isFile && (it.extension == "html" || it.extension == "htm") }
            .forEach { f ->
                val content = f.readText()
                if (content.contains("{{DOCS_VERSION}}"))
                    f.writeText(content.replace("{{DOCS_VERSION}}", ver))
            }
        // 3. Rsync landing to web root (exclude /docs so version folders are untouched)
        val exitCode = ProcessBuilder(
            "rsync", "-ravz", "-e", "ssh -p 1971",
            "--exclude=/docs",
            "${landingDir.absolutePath}/", "teras@yot.is:~/web/stormify.org/"
        ).inheritIO().start().waitFor()
        if (exitCode != 0)
            throw GradleException("Landing rsync failed with exit code $exitCode")
        println("✓ Landing updated: https://stormify.org/ → /docs/$ver/")
    }
}

tasks.register("syncReadmeUrls") {
    group = "documentation"
    description = "Rewrite README.md URLs to point to /docs/<projectVersion>/ and stormify-examples/tree/<projectVersion>. Idempotent."
    outputs.upToDateWhen { false }
    doLast {
        val ver = project.version.toString().removeSuffix("-SNAPSHOT")
        if (ver.isBlank() || ver == "unspecified")
            throw GradleException("project.version is not set")
        val readme = file("README.md")
        val original = readme.readText()
        // Step 1 — normalize: strip any previous version-specific rewrites back to canonical form
        val normalized = original
            .replace(Regex("""https://stormify\.org/docs/[0-9][^/"]*/"""), "https://stormify.org/docs/")
            .replace(Regex("""https://github\.com/teras/stormify-examples/tree/[^"\)]+"""), "https://github.com/teras/stormify-examples")
        // Step 2 — apply current-version rewrites
        val out = normalized
            .replace("https://stormify.org/docs/", "https://stormify.org/docs/$ver/")
            .replace(Regex("""https://github\.com/teras/stormify-examples(?=["\)])"""), "https://github.com/teras/stormify-examples/tree/$ver")
        if (out != original) {
            readme.writeText(out)
            println("✓ README.md synced to version $ver")
        } else {
            println("README.md already in sync (version $ver)")
        }
    }
}
