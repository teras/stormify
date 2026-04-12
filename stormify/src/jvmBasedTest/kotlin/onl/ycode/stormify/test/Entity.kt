package onl.ycode.stormify.test

import kotlinx.atomicfu.atomic
import onl.ycode.stormify.EntityMeta
import onl.ycode.stormify.EntityRegistrar
import onl.ycode.stormify.PropertyMeta
import onl.ycode.stormify.TypeUtils.castTo

data class Entity(
    var id: Int = 0,
    var name: String = "",
    var family: String = "",
    var age: Int? = 0,
    var street: String = "",
    var city: String = "",
    var state: String = "",
    var zip: String = "",
    var country: String = "",
    var phone: String = "",
    var email: String = "",
    var website: String? = null,
    var notes: String? = null,
    var spouse: String? = null,
)

object TestEntities : EntityRegistrar {
    private val initialized = atomic(false)

    override fun register() {
        if (!initialized.compareAndSet(false, true)) return

        EntityMeta.register(EntityMeta(
            Entity::class,
            { Entity() },
            listOf(
                PropertyMeta("id", Int::class, false, { it.id }, { e, v, s -> e.id = castTo(Int::class, v, s) ?: throw IllegalArgumentException("id cannot be null") }, null, true, null, true, true, false),
                PropertyMeta("name", String::class, false, { it.name }, { e, v, s -> e.name = castTo(String::class, v, s) ?: throw IllegalArgumentException("name cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("family", String::class, false, { it.family }, { e, v, s -> e.family = castTo(String::class, v, s) ?: throw IllegalArgumentException("family cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("age", Int::class, false, { it.age }, { e, v, s -> e.age = castTo(Int::class, v, s) }, null, false, null, true, true, false),
                PropertyMeta("street", String::class, false, { it.street }, { e, v, s -> e.street = castTo(String::class, v, s) ?: throw IllegalArgumentException("street cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("city", String::class, false, { it.city }, { e, v, s -> e.city = castTo(String::class, v, s) ?: throw IllegalArgumentException("city cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("state", String::class, false, { it.state }, { e, v, s -> e.state = castTo(String::class, v, s) ?: throw IllegalArgumentException("state cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("zip", String::class, false, { it.zip }, { e, v, s -> e.zip = castTo(String::class, v, s) ?: throw IllegalArgumentException("zip cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("country", String::class, false, { it.country }, { e, v, s -> e.country = castTo(String::class, v, s) ?: throw IllegalArgumentException("country cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("phone", String::class, false, { it.phone }, { e, v, s -> e.phone = castTo(String::class, v, s) ?: throw IllegalArgumentException("phone cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("email", String::class, false, { it.email }, { e, v, s -> e.email = castTo(String::class, v, s) ?: throw IllegalArgumentException("email cannot be null") }, null, false, null, true, true, false),
                PropertyMeta("website", String::class, false, { it.website }, { e, v, s -> e.website = castTo(String::class, v, s) }, null, false, null, true, true, false),
                PropertyMeta("notes", String::class, false, { it.notes }, { e, v, s -> e.notes = castTo(String::class, v, s) }, null, false, null, true, true, false),
                PropertyMeta("spouse", String::class, false, { it.spouse }, { e, v, s -> e.spouse = castTo(String::class, v, s) }, null, false, null, true, true, false),
            ),
            "ENTITY"
        ))
    }
}
