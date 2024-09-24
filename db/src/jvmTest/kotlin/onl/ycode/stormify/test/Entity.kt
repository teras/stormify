package onl.ycode.stormify.test

import onl.ycode.stormify.QueryException
import onl.ycode.stormify.TableInfo
import onl.ycode.stormify.TableInfo.Companion.register
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


fun registerAll() {
    fun err(name: String, cls: String): Nothing = throw QueryException("$name cannot be null in class $cls")
    register(
        TableInfo(
            Entity::class,
            "ENTITY",
            { Entity() },
            { entity, name, value, stormify ->
                when (name.lowercase()) {
                    "id" -> {
                        entity.id = castTo(Int::class, value, stormify) ?: err("id", "Entity")
                        true
                    }

                    "name" -> {
                        entity.name = castTo(String::class, value, stormify) ?: err("name", "Entity")
                        true
                    }

                    "family" -> {
                        entity.family = castTo(String::class, value, stormify) ?: err("family", "Entity")
                        true
                    }

                    "age" -> {
                        entity.age = castTo(Int::class, value, stormify)
                        true
                    }

                    "street" -> {
                        entity.street = castTo(String::class, value, stormify) ?: err("street", "Entity")
                        true
                    }

                    "city" -> {
                        entity.city = castTo(String::class, value, stormify) ?: err("city", "Entity")
                        true
                    }

                    "state" -> {
                        entity.state = castTo(String::class, value, stormify) ?: err("state", "Entity")
                        true
                    }

                    "zip" -> {
                        entity.zip = castTo(String::class, value, stormify) ?: err("zip", "Entity")
                        true
                    }

                    "country" -> {
                        entity.country = castTo(String::class, value, stormify) ?: err("country", "Entity")
                        true
                    }

                    "phone" -> {
                        entity.phone = castTo(String::class, value, stormify) ?: err("phone", "Entity")
                        true
                    }

                    "email" -> {
                        entity.email = castTo(String::class, value, stormify) ?: err("email", "Entity")
                        true
                    }

                    "website" -> {
                        entity.website = castTo(String::class, value, stormify)
                        true
                    }

                    "notes" -> {
                        entity.notes = castTo(String::class, value, stormify)
                        true
                    }

                    "spouse" -> {
                        entity.spouse = castTo(String::class, value, stormify)
                        true
                    }

                    else -> false
                }
            },
            listOf("id"),
            listOf("ID"),
            listOf(Int::class),
            listOf(""),
            { listOf(it.id) },
            listOf(
                "name",
                "age",
                "street",
                "city",
                "state",
                "zip",
                "country",
                "phone",
                "email",
                "website",
                "notes",
                "spouse"
            ),
            listOf(
                "NAME",
                "AGE",
                "STREET",
                "CITY",
                "STATE",
                "ZIP",
                "COUNTRY",
                "PHONE",
                "EMAIL",
                "WEBSITE",
                "NOTES",
                "SPOUSE"
            ),
            listOf(
                String::class,
                String::class,
                Int::class,
                String::class,
                String::class,
                String::class,
                String::class,
                String::class,
                String::class,
                String::class,
                String::class,
                String::class
            ),
            {
                listOf(
                    it.name,
                    it.age,
                    it.street,
                    it.city,
                    it.state,
                    it.zip,
                    it.country,
                    it.phone,
                    it.email,
                    it.website,
                    it.notes,
                    it.spouse
                )
            },
            "SELECT * FROM ENTITY WHERE ID = ?",
            "",
            "",
            ""
        )
    )
}

