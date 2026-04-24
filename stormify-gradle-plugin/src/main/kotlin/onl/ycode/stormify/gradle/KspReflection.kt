// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.Task
import org.gradle.api.provider.MapProperty

/**
 * Reflectively set a per-task KSP processor option. `kspConfig.processorOptions`
 * is a `MapProperty<String,String>` exposed on the nested `KspGradleConfig`
 * object accessed via `task.getKspConfig()`. Setting it scoped to a specific
 * task (rather than via the project-wide `ksp { arg(...) }` extension) lets
 * us pass a different `stormify.metaOutputDir` per target.
 */
@Suppress("UNCHECKED_CAST")
internal fun setKspProcessorOption(kspTask: Task, key: String, value: String) {
    try {
        val cfgGetter = kspTask.javaClass.methods.firstOrNull { it.name == "getKspConfig" } ?: return
        val cfg = cfgGetter.invoke(kspTask) ?: return
        val optsGetter = cfg.javaClass.methods.firstOrNull { it.name == "getProcessorOptions" } ?: return
        val opts = optsGetter.invoke(cfg) as? MapProperty<String, String> ?: return
        opts.put(key, value)
    } catch (e: Throwable) {
        kspTask.logger.error("Stormify: KSP setup failed on ${kspTask.name} — generated entities will be empty. Check your Kotlin/KSP versions. (${e.message})")
    }
}
