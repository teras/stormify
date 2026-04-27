package onl.ycode.stormify.schemasync.classifier

import onl.ycode.stormify.schemasync.config.ConfigStore
import onl.ycode.stormify.schemasync.model.SlotCategory
import java.nio.file.Files

private val sampleText = listOf(
    "customer_name", "first_name", "company_title", "email", "homepage_url",
    "address_line1", "city", "phone", "status_code", "currency_code",
    "slug", "summary", "description", "notes", "html_body",
)

private val sampleIntegral = listOf(
    "user_id", "order_id", "parent_id", "count", "items_count",
    "year", "month_number", "version", "percent_complete",
    "is_active", "size_bytes",
)

private val sampleDecimal = listOf(
    "order_total", "unit_price", "tax_amount", "discount",
    "balance", "exchange_rate", "tax_rate", "interest_rate",
    "latitude", "longitude", "temperature", "weight_kg",
)

fun main() {
    // Load config from a fresh temp file so we exercise the bootstrap path
    // and don't pollute the user's project on every demo run.
    val tempFile = Files.createTempFile("schema-sync-demo", ".toml").also { Files.delete(it) }
    val (config, freshlyBootstrapped, path) = ConfigStore(tempFile).load()
    println("Config loaded from $path (bootstrapped=$freshlyBootstrapped)")
    println(
        "Slots: text=${config.slots.text.size} " +
            "integral=${config.slots.integral.size} " +
            "decimal=${config.slots.decimal.size}",
    )
    println()

    SchemaClassifier().use { classifier ->
        classifier.seed(config.seeds)

        section("TEXT", SlotCategory.TEXT, sampleText, classifier)
        section("INTEGRAL", SlotCategory.INTEGRAL, sampleIntegral, classifier)
        section("DECIMAL", SlotCategory.DECIMAL, sampleDecimal, classifier)
    }

    Files.deleteIfExists(tempFile)
}

private fun section(
    label: String,
    category: SlotCategory,
    columns: List<String>,
    classifier: SchemaClassifier,
) {
    println("=== $label ===")
    columns.forEach { col ->
        val top = classifier.classify(category, col, topN = 3)
        val rendered = if (top.isEmpty()) {
            "(no suggestion)"
        } else {
            top.joinToString("  ") { "${it.slotKey}=${"%.2f".format(it.score)}" }
        }
        println("  ${col.padEnd(22)}  $rendered")
    }
    println()
}
