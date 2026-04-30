package onl.ycode.stormify.schemasync.entity.source

import java.nio.file.Path

/** gradle :schema-sync:writerDemo --args='<file.kt> <ClassName> add|transient <prop> [<type> [nullable]]' */
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println("usage: writerDemo <file.kt> <ClassName> add|transient <prop> [<type> [nullable]]")
        return
    }
    val path = Path.of(args[0])
    val className = args[1]
    val op = args[2]
    val prop = args[3]
    val edit = when (op) {
        "add" -> EntityWriter.Edit.AddProperty(
            className = className,
            propertyName = prop,
            kotlinType = args.getOrNull(4) ?: "String",
            nullable = args.getOrNull(5) == "nullable",
        )
        "transient" -> EntityWriter.Edit.MarkTransient(className, prop)
        else -> { System.err.println("op must be 'add' or 'transient'"); return }
    }
    PsiEnvironment().use { env ->
        val changed = EntityWriter.applyEdits(env, path, listOf(edit))
        println(if (changed) "modified $path" else "no change to $path")
    }
}
