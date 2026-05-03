package onl.ycode.stormify.schemasync.entity.source

import java.nio.file.Path

/** gradle :schema-sync:scannerDemo --args='<src-root> [<src-root>...]' */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: scannerDemo <src-root> [<src-root>...]")
        return
    }
    val entities = EntityScanner.scan(args.map(Path::of))
    println("Found ${entities.size} entities")
    for (e in entities) {
        println("  ${e.className} → ${e.table}")
        for (f in e.fields) {
            val flags = buildList {
                if (f.primaryKey) add("PK")
                if (!f.nullable) add("NN")
                if (f.autoIncrement) add("AI")
                if (f.referencedEntity != null) add("FK→${f.referencedEntity}")
                if (f.sequence.isNotEmpty()) add("seq=${f.sequence}")
                if (!f.creatable) add("noCreate")
                if (!f.updatable) add("noUpdate")
            }.joinToString(",")
            val tag = if (flags.isNotEmpty()) " [$flags]" else ""
            println("    ${f.name.padEnd(20)} ${f.column.padEnd(20)} ${f.type}$tag")
        }
    }
}
