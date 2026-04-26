// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.MapProperty

/**
 * Wire the `stormify.metaOutputDir` processor option (and declare [metaDirPath]
 * as a task output) on every KSP task whose name is in [kspTaskNames].
 * Declaring the dir as an output lets Gradle re-run KSP after `clean` —
 * annproc writes JSONs there via plain Java I/O, which the up-to-date check
 * would otherwise miss.
 */
internal fun wireKspMetaDir(project: Project, kspTaskNames: Set<String>, metaDirPath: String) {
    project.tasks.matching { it.name in kspTaskNames }.configureEach { task ->
        setKspProcessorOption(task, "stormify.metaOutputDir", metaDirPath)
        task.outputs.dir(metaDirPath)
    }
}

/**
 * Reflectively set a per-task KSP processor option. `kspConfig.processorOptions`
 * is a `MapProperty<String,String>` exposed on a nested `KspGradleConfig`
 * accessed via `task.getKspConfig()`. Setting it scoped to a specific task
 * (rather than via the project-wide `ksp { arg(...) }` extension) lets us
 * pass a different `stormify.metaOutputDir` per target.
 */
@Suppress("UNCHECKED_CAST")
internal fun setKspProcessorOption(kspTask: Task, key: String, value: String) {
    val ctx = "KSP task '${kspTask.name}'"
    val cfg = kspTask.callGetterOrThrow("getKspConfig", ctx)
    val opts = cfg.callGetterOrThrow("getProcessorOptions", ctx) as? MapProperty<String, String>
        ?: throw GradleException("Stormify: $ctx — processorOptions is not MapProperty<String,String>.")
    opts.put(key, value)
}
