package onl.ycode.stormify.schemasync.config

import com.akuleshov7.ktoml.Toml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads and (later) saves `.schema-sync.toml`. On first launch the project
 * file is bootstrapped from the bundled `default-config.toml` resource.
 */
class ConfigStore(val projectFile: Path) {

    /**
     * Reads the project config file, or copies the bundled default into place
     * on the first launch and reads it from there.
     */
    fun load(): LoadResult {
        val freshlyBootstrapped = !Files.exists(projectFile)
        if (freshlyBootstrapped) {
            val resourceText = ConfigStore::class.java.classLoader
                .getResourceAsStream(DEFAULT_CONFIG_RESOURCE)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("Bundled $DEFAULT_CONFIG_RESOURCE missing from classpath")
            Files.writeString(projectFile, resourceText)
        }
        val tomlText = Files.readString(projectFile)
        val config = Toml.decodeFromString(SchemaSyncConfig.serializer(), tomlText)
        return LoadResult(config, freshlyBootstrapped, projectFile)
    }

    /** Persist the current config. Comments in the TOML file will be lost. */
    fun save(config: SchemaSyncConfig) {
        val tomlText = Toml.encodeToString(SchemaSyncConfig.serializer(), config)
        Files.writeString(projectFile, tomlText)
    }

    data class LoadResult(
        val config: SchemaSyncConfig,
        val freshlyBootstrapped: Boolean,
        val path: Path,
    )

    companion object {
        const val DEFAULT_CONFIG_RESOURCE = "default-config.toml"
        const val PROJECT_FILE_NAME = ".schema-sync.toml"

        fun forCurrentDirectory(): ConfigStore =
            ConfigStore(Path.of(PROJECT_FILE_NAME).toAbsolutePath())
    }
}
