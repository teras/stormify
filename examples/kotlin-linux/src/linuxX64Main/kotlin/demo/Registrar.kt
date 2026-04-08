package demo

import onl.ycode.stormify.EntityMeta
import onl.ycode.stormify.EntityRegistrar
import onl.ycode.stormify.PropertyMeta
import onl.ycode.stormify.TypeUtils.castTo

/**
 * Entity registrar for native targets.
 *
 * On JVM, Stormify can discover entities via reflection. On native platforms,
 * entity metadata must be registered explicitly. Normally this code is generated
 * automatically by the KSP annotation processor (annproc). Here it is written
 * manually to keep the build simple for this single-target demo.
 *
 * In a multiplatform project with a JVM target, use the annproc KSP processor
 * which generates this code at compile time — see the kotlin-multiplatform example.
 */
object GeneratedEntities : EntityRegistrar {
    private var initialized = false

    override fun register() {
        if (initialized) return
        initialized = true

        EntityMeta.register(
            EntityMeta(
                User::class,
                { User() },
                listOf(
                    PropertyMeta(
                        "id", Int::class, false,
                        { it.id },
                        { e, v, s -> e.id = castTo(Int::class, v, s) as Int },
                        null, true, null, true, true, true, false
                    ),
                    PropertyMeta(
                        "name", String::class, false,
                        { it.name },
                        { e, v, s -> e.name = castTo(String::class, v, s) as String },
                        null, false, null, false, true, true, false
                    ),
                    PropertyMeta(
                        "email", String::class, false,
                        { it.email },
                        { e, v, s -> e.email = castTo(String::class, v, s) as String },
                        null, false, null, false, true, true, false
                    )
                ),
                "user"
            )
        )

        EntityMeta.register(
            EntityMeta(
                Task::class,
                { Task() },
                listOf(
                    PropertyMeta(
                        "id", Int::class, false,
                        { it.id },
                        { e, v, s -> e.id = castTo(Int::class, v, s) as Int },
                        null, true, null, true, true, true, false
                    ),
                    PropertyMeta(
                        "title", String::class, false,
                        { it.title },
                        { e, v, s -> e.title = castTo(String::class, v, s) as String },
                        null, false, null, false, true, true, false
                    ),
                    PropertyMeta(
                        "description", String::class, false,
                        { it.description },
                        { e, v, s -> e.description = castTo(String::class, v, s) as String },
                        null, false, null, false, true, true, false
                    ),
                    PropertyMeta(
                        "isCompleted", Boolean::class, false,
                        { it.isCompleted },
                        { e, v, s -> e.isCompleted = castTo(Boolean::class, v, s) as Boolean },
                        "is_completed", false, null, false, true, true, false
                    ),
                    PropertyMeta(
                        "user", User::class, true,
                        { it.user },
                        @Suppress("UNCHECKED_CAST")
                        { e, v, s -> e.user = castTo(User::class, v, s) as? User },
                        "user_id", false, null, false, true, true, false
                    )
                ),
                "task"
            )
        )
    }
}
