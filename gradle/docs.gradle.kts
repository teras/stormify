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
//                  Injects `<meta name="robots" content="noindex,nofollow">` so previews
//                  are not indexed by search engines. Released folders have no marker
//                  and are never touched.
// releaseDocs    — strip .devel-marker from /docs/<projectVersion>/ (makes it immutable),
//                  remove the noindex meta from every page, update the landing page at
//                  stormify.org/ to point to it, and write a fresh /sitemap.xml that
//                  points to the released version.
//
// ─── Workflow ───────────────────────────────────────────────────────────────
// Publish a preview:
//   gradle publishDocs           # build + deploy /docs/<ver>/ (replaces previous preview)
//
// Release (tag-triggered CI publishes to Maven Central):
//   scripts/bump-version.sh <ver>
//   # Add CHANGELOG entry [<ver>] + footnote link manually
//   # Review + commit submodule examples/ if it has changes
//   git commit -am "release <ver>" && git tag v<ver> && git push --follow-tags
//   # CI picks up the tag, runs `gradle publish` → Maven Central, wait for Central sync
//   gradle publishDocs           # preview at /docs/<ver>/ (optional smoke test)
//   gradle releaseDocs           # strips marker + updates landing

// Deployment target for publishDocs / releaseDocs. Read from local.properties
// (gitignored) so the SSH host/port/remote root stay out of version control.
data class DocsDeploy(val sshHost: String, val sshPort: String, val remoteRoot: String)
fun readDocsDeploy(): DocsDeploy {
    val props = java.util.Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    fun need(key: String, example: String): String = props.getProperty(key)
        ?: throw GradleException("Missing '$key' in local.properties (e.g. $key=$example)")
    return DocsDeploy(
        sshHost = need("docs.ssh.host", "user@example.com"),
        sshPort = need("docs.ssh.port", "22"),
        remoteRoot = need("docs.remote.root", "~/web/example.com")
    )
}

// Docs outputs live under docs/build/ (not under the root project build/), so
// the default `clean` task never touches them. Hook an explicit cleanup in.
val cleanDocs = tasks.register<Delete>("cleanDocs") {
    group = "documentation"
    description = "Delete docs/build/, docs/build-landing/, and any leftover API staging dirs (hooks into `clean`)"
    delete(
        file("docs/build"),
        file("docs/build-landing"),
        // Staging dirs used by createDocs for MkDocs pickup; normally cleaned up in
        // a finally block, but deleted here too in case a previous build crashed.
        file("docs/src/api-stormify"),
        file("docs/src/api-kdbc-kotlin"),
        file("docs/src/kdbc-c")
    )
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

        // Staging dirs under docs/src so MkDocs picks up API reference trees
        // as regular site assets (no post-build patching of docs/build/docs/).
        // Gitignored; regenerated on every createDocs run.
        val apiStormifyStage = file("docs/src/api-stormify")
        val apiKdbcKotlinStage = file("docs/src/api-kdbc-kotlin")
        val kdbcCStage = file("docs/src/kdbc-c")
        listOf(apiStormifyStage, apiKdbcKotlinStage, kdbcCStage).forEach { it.deleteRecursively() }
        try {
            // 1. Doxygen (KDBC C API): override OUTPUT_DIRECTORY via stdin so Doxyfile
            //    stays untouched. Writes to docs/src/kdbc-c/html/.
            val doxygen = ProcessBuilder("sh", "-c",
                "{ cat Doxyfile; echo; echo 'OUTPUT_DIRECTORY=../../../docs/src/kdbc-c'; } | doxygen -")
                .directory(file("kdbc/src/c"))
                .inheritIO()
            val doxygenRc = doxygen.start().waitFor()
            if (doxygenRc != 0) throw GradleException("Doxygen failed (exit $doxygenRc)")

            // 2. Dokka: stage API HTML under docs/src/ for MkDocs to pick up.
            file("stormify/build/dokka/html").copyRecursively(apiStormifyStage, overwrite = true)
            file("kdbc/build/dokka/html").copyRecursively(apiKdbcKotlinStage, overwrite = true)

            // 3. MkDocs: main documentation site (site_name picked up from env).
            //    With staged API trees under docs/src/, MkDocs ships them in docs/build/docs/.
            val mkdocs = ProcessBuilder("mkdocs", "build")
                .directory(file("docs"))
                .inheritIO()
            mkdocs.environment()["STORMIFY_VERSION_NAME"] = "Stormify $ver"
            val mkdocsRc = mkdocs.start().waitFor()
            if (mkdocsRc != 0) throw GradleException("MkDocs build failed (exit $mkdocsRc)")

            // 4. Inject back-link bar into API reference pages in the built site.
            val inject = file("docs/inject-backlink.sh").absolutePath
            ProcessBuilder("sh", inject, "docs/build/docs/api-stormify").inheritIO().start().waitFor()
            ProcessBuilder("sh", inject, "docs/build/docs/api-kdbc-kotlin").inheritIO().start().waitFor()
            ProcessBuilder("sh", inject, "docs/build/docs/kdbc-c").inheritIO().start().waitFor()

            // 5. Copy static assets with {{DOCS_VERSION}} substituted to current version
            file("docs/static").copyRecursively(file("docs/build"), overwrite = true)
            file("docs/build").walkTopDown()
                .filter { it.isFile && (it.extension == "html" || it.extension == "htm") }
                .forEach { f ->
                    val content = f.readText()
                    if (content.contains("{{DOCS_VERSION}}"))
                        f.writeText(content.replace("{{DOCS_VERSION}}", ver))
                }
        } finally {
            // Remove the staged API trees so docs/src/ stays clean for the next run
            // and for ordinary `mkdocs serve` sessions that don't need API docs.
            listOf(apiStormifyStage, apiKdbcKotlinStage, kdbcCStage).forEach { it.deleteRecursively() }
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
        val deploy = readDocsDeploy()
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
        // Inject `<meta name="robots" content="noindex,nofollow">` into every preview HTML so
        // search engines do not index in-progress documentation. The `releaseDocs` task removes
        // this tag server-side once the version is promoted from preview to released.
        val noindex = """
            find docs/build/docs -type f -name '*.html' -exec sed -i \
              -e '/<meta name="robots"/d' \
              -e 's|<head>|<head><meta name="robots" content="noindex,nofollow">|' \
              {} +
        """.trimIndent()
        val noindexRc = ProcessBuilder("sh", "-c", noindex).inheritIO().start().waitFor()
        if (noindexRc != 0) throw GradleException("noindex injection failed (exit $noindexRc)")
        // Remove any previous preview folder on the server (any /docs/<X>/ that carries
        // .devel-marker and is not the current version). Released folders have no marker
        // and are left alone.
        val cleanup = """
            set -euo pipefail
            cd ${deploy.remoteRoot}/docs 2>/dev/null || exit 0
            for d in */; do
                d="${'$'}{d%/}"
                if [ -f "${'$'}d/.devel-marker" ] && [ "${'$'}d" != "$ver" ]; then
                    echo "Removing stale preview: /docs/${'$'}d/"
                    rm -rf -- "${'$'}d"
                fi
            done
        """.trimIndent()
        val cleanupRc = ProcessBuilder("ssh", "-p", deploy.sshPort, deploy.sshHost, "bash -s")
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
            "rsync", "-ravz", "-e", "ssh -p ${deploy.sshPort}", "--delete",
            "docs/build/docs/", "${deploy.sshHost}:${deploy.remoteRoot}/docs/$ver/"
        ).inheritIO().start().waitFor()
        if (exitCode != 0)
            throw GradleException("rsync failed with exit code $exitCode — preview docs not uploaded")
        // Tag as preview
        val markRc = ProcessBuilder("ssh", "-p", deploy.sshPort, deploy.sshHost,
            "touch ${deploy.remoteRoot}/docs/$ver/.devel-marker").inheritIO().start().waitFor()
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
        val deploy = readDocsDeploy()
        // 1. Server-side: validate preview exists and strip the marker
        val remote = """
            set -euo pipefail
            cd ${deploy.remoteRoot}/docs
            if [ ! -d "$ver" ]; then
                echo "ERROR: /docs/$ver/ not found on server — publish it first" >&2; exit 1
            fi
            if [ ! -f "$ver/.devel-marker" ]; then
                echo "ERROR: /docs/$ver/ has no .devel-marker — already released?" >&2; exit 1
            fi
            rm -f "$ver/.devel-marker"
            echo "✓ Released /docs/$ver/ (marker removed)"
            # Strip the noindex meta from every HTML in the released folder so search engines
            # can index it now that it is the canonical version.
            find "$ver" -type f -name '*.html' -exec sed -i \
                's|<meta name="robots" content="noindex,nofollow">||g' {} +
            echo "✓ noindex meta stripped from /docs/$ver/"
            cat > index.html <<'HTML'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="refresh" content="0; url=/docs/$ver/">
<link rel="canonical" href="https://stormify.org/docs/$ver/">
<title>Stormify Documentation</title>
</head>
<body>
<p>Redirecting to <a href="/docs/$ver/">/docs/$ver/</a>…</p>
</body>
</html>
HTML
            echo "✓ /docs/index.html redirects to /docs/$ver/"
        """.trimIndent()
        val remoteRc = ProcessBuilder("ssh", "-p", deploy.sshPort, deploy.sshHost, "bash -s")
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
        // 3. Generate a sitemap index pointing to the current released docs sitemap.
        //    MkDocs Material emits per-version sitemaps at /docs/<ver>/sitemap.xml(.gz);
        //    this index lets search engines find the landing page and the canonical version
        //    without any per-version edits to robots.txt.
        val today = java.time.LocalDate.now().toString()
        val sitemap = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url>
                <loc>https://stormify.org/</loc>
                <lastmod>$today</lastmod>
                <priority>1.0</priority>
              </url>
              <url>
                <loc>https://stormify.org/docs/$ver/</loc>
                <lastmod>$today</lastmod>
                <priority>0.9</priority>
              </url>
            </urlset>
        """.trimIndent() + "\n"
        file("$landingDir/sitemap.xml").writeText(sitemap)
        // 4. Rsync landing to web root (exclude /docs so version folders are untouched)
        val exitCode = ProcessBuilder(
            "rsync", "-ravz", "-e", "ssh -p ${deploy.sshPort}",
            "--exclude=/docs",
            "${landingDir.absolutePath}/", "${deploy.sshHost}:${deploy.remoteRoot}/"
        ).inheritIO().start().waitFor()
        if (exitCode != 0)
            throw GradleException("Landing rsync failed with exit code $exitCode")
        println("✓ Landing updated: https://stormify.org/ → /docs/$ver/")
        println("✓ Sitemap published: https://stormify.org/sitemap.xml (→ /docs/$ver/)")
    }
}

