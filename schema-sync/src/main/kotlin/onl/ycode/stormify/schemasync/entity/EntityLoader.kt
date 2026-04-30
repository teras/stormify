package onl.ycode.stormify.schemasync.entity

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Loads [KotlinEntity] definitions from a JSON file produced by the entity plugin (or by hand). */
object EntityLoader {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(path: Path): List<KotlinEntity> {
        val text = Files.readString(path)
        return json.decodeFromString(EntityCatalog.serializer(), text).entities
    }
}
