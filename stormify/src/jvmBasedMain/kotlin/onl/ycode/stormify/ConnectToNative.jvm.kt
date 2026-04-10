@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.stormify

import kotlin.reflect.KClass

actual typealias NativeBigInteger = java.math.BigInteger
internal actual fun systemMillis() = System.currentTimeMillis()

private val supportsIonspinBigNumbers = try {
    com.ionspin.kotlin.bignum.decimal.BigDecimal::class.simpleName
    true
} catch (e: Throwable) {
    false
}

private val supportsKotlinxDatetime = try {
    kotlinx.datetime.LocalDate::class.simpleName
    true
} catch (e: Throwable) {
    false
}

internal actual fun getNativeAllPrimitives(): Collection<KClass<*>> =
    listOf(
        java.math.BigInteger::class,
        java.math.BigDecimal::class,
        java.util.Date::class,
        java.sql.Date::class,
        java.sql.Timestamp::class,
        java.sql.Time::class
    ) +
            (if (supportsIonspinBigNumbers) listOf(
                com.ionspin.kotlin.bignum.decimal.BigDecimal::class,
                com.ionspin.kotlin.bignum.integer.BigInteger::class
            ) else emptyList()) +
            (if (supportsKotlinxDatetime) listOf(
                kotlinx.datetime.LocalDate::class,
                kotlinx.datetime.LocalDateTime::class,
                kotlinx.datetime.LocalTime::class,
                kotlin.time.Instant::class
            ) else emptyList())

internal actual class WeakRef<T : Any> actual constructor(referent: T) {
    private val ref = java.lang.ref.WeakReference(referent)
    actual fun get(): T? = ref.get()
}

internal actual fun transformResultValue(value: Any?): Any? {
    if (value == null) return null
    if (value is java.sql.Clob) return value.getSubString(1, value.length().toInt())
    if (value is java.sql.Blob) return value.getBytes(1, value.length().toInt())
    return value
}

actual val Any.isOtherPrimitive: Boolean
    get() = this is java.util.Date ||
            this is java.time.temporal.Temporal
            || (supportsIonspinBigNumbers && this is com.ionspin.kotlin.bignum.BigNumber<*>)
            || (supportsKotlinxDatetime && (this is kotlinx.datetime.LocalDateTime
                    || this is kotlinx.datetime.LocalDate
                    || this is kotlinx.datetime.LocalTime
                    || this is kotlin.time.Instant))

@Suppress("UNCHECKED_CAST")
internal actual fun <T : Any> enumFromInt(enumClass: KClass<T>, value: Int): T? {
    val constants = enumClass.java.enumConstants as? Array<out Enum<*>> ?: return null
    return if (constants.firstOrNull() is DbValue) {
        constants.firstOrNull { (it as DbValue).dbValue == value } as T?
    } else {
        constants.getOrNull(value) as T?
    }
}

internal actual fun enumToInt(value: Enum<*>): Int =
    if (value is DbValue) value.dbValue else value.ordinal

@Suppress("UNCHECKED_CAST")
internal actual fun <T : Any> enumFromName(enumClass: KClass<T>, name: String): T? {
    val constants = enumClass.java.enumConstants as? Array<out Enum<*>> ?: return null
    return constants.firstOrNull { it.name.equals(name, ignoreCase = true) } as T?
}

internal actual fun isEnumClass(klass: KClass<*>): Boolean = klass.java.isEnum

@Suppress("UNCHECKED_CAST")
internal actual fun enumEntries(klass: KClass<*>): Array<out Enum<*>>? =
    klass.java.enumConstants as? Array<out Enum<*>>

internal actual fun <T : Any> tryReflection(type: KClass<T>): EntityMeta<T>? {
    val jClass = type.java
    // Build table name override from @DbTable or JPA @Table/@Entity
    val tableNameOverride = jClass.getAnnotation(DbTable::class.java)?.name?.takeIf { it.isNotBlank() }
        ?: jClass.annotations.find { it.annotationClass.qualifiedName == "javax.persistence.Table" }
            ?.let { ann -> ann.annotationClass.java.getMethod("name").invoke(ann) as? String }
            ?.takeIf { it.isNotBlank() }

    val kProps = type.members.filterIsInstance<kotlin.reflect.KProperty1<T, *>>()
        .filter { it.name.first().isLetter() } // Exclude internal/synthetic fields
    if (kProps.isEmpty()) return null

    val properties = kProps.mapNotNull { kProp ->
        val propName = kProp.name
        val capitalizedName = propName.replaceFirstChar { it.uppercase() }

        // Resolve Java field (walk class hierarchy for inherited fields) and getter
        val jField = run {
            var cls: Class<*>? = jClass
            while (cls != null) {
                try { return@run cls.getDeclaredField(propName) } catch (_: Exception) {}
                cls = cls.superclass
            }
            null
        }
        val javaGetter = try { jClass.getMethod("get$capitalizedName") } catch (_: Exception) {
            try { jClass.getMethod("is$capitalizedName") } catch (_: Exception) {
                // Boolean property named "isFoo" — getter is isFoo(), not getIsFoo()
                if (propName.startsWith("is") && propName.length > 2)
                    try { jClass.getMethod(propName) } catch (_: Exception) { null }
                else null
            }
        }

        // Collect annotations from all sources (JPA supports both field-access and property-access):
        // 1. Java field annotations (standard Kotlin backing field or Java POJO field)
        // 2. Java getter annotations (JPA property-access pattern: @Id on getId())
        // 3. Kotlin property annotations (includes annotations on delegated properties like `by db()`)
        // 4. Constructor parameter annotations (Kotlin 2.x defaults annotations on constructor
        //    `var`/`val` params to the parameter target, not the property — pick them up here
        //    so users don't need -Xannotation-default-target=param-property).
        //    Walk the class hierarchy so inherited constructor properties are found too.
        val ctorParamAnnotations: List<Annotation> = run {
            var cls: Class<*>? = jClass
            while (cls != null) {
                for (ctor in cls.kotlin.constructors) {
                    val param = ctor.parameters.find { it.name == propName }
                    if (param != null) return@run param.annotations.toList()
                }
                cls = cls.superclass
            }
            emptyList()
        }
        val annotations = (jField?.annotations?.toList() ?: emptyList()) +
                (javaGetter?.annotations?.toList() ?: emptyList()) +
                (kProp.annotations) +
                ctorParamAnnotations

        // Check transient: Java keyword, Kotlin @Transient, JPA @Transient
        val isJavaTransient = jField != null && java.lang.reflect.Modifier.isTransient(jField.modifiers)
        val isTransient = isJavaTransient || annotations.any {
            it is Transient || it.annotationClass.qualifiedName == "javax.persistence.Transient"
        }

        val dbField = annotations.filterIsInstance<DbField>().firstOrNull()
        val jpaId = annotations.any { it.annotationClass.qualifiedName == "javax.persistence.Id" }
        val jpaColumn = annotations.find { it.annotationClass.qualifiedName == "javax.persistence.Column" }
        val jpaJoinColumn = annotations.find { it.annotationClass.qualifiedName == "javax.persistence.JoinColumn" }
        val jpaSequence = annotations.find { it.annotationClass.qualifiedName == "javax.persistence.SequenceGenerator" }

        val dbNameOverride = dbField?.name?.takeIf { it.isNotBlank() }
            ?: (jpaColumn ?: jpaJoinColumn)?.let { ann ->
                try { ann.annotationClass.java.getMethod("name").invoke(ann) as? String } catch (_: Exception) { null }
            }?.takeIf { it.isNotBlank() }

        val isPrimaryKey = dbField?.primaryKey ?: jpaId
        val sequence = dbField?.primarySequence?.takeIf { it.isNotBlank() }
            ?: jpaSequence?.let { ann ->
                try { ann.annotationClass.java.getMethod("name").invoke(ann) as? String } catch (_: Exception) { null }
            }?.takeIf { it.isNotBlank() }

        val isAutoIncrement = (dbField?.autoIncrement ?: false) || annotations.any { ann ->
            if (ann.annotationClass.qualifiedName == "javax.persistence.GeneratedValue") {
                try {
                    val strategy = ann.annotationClass.java.getMethod("strategy").invoke(ann)
                    strategy?.toString() == "IDENTITY"
                } catch (_: Exception) { false }
            } else false
        }

        val isCreatable = dbField?.creatable ?: (jpaColumn?.let { ann ->
            try { ann.annotationClass.java.getMethod("insertable").invoke(ann) as? Boolean } catch (_: Exception) { null }
        } ?: true)
        val isUpdatable = dbField?.updatable ?: (jpaColumn?.let { ann ->
            try { ann.annotationClass.java.getMethod("updatable").invoke(ann) as? Boolean } catch (_: Exception) { null }
        } ?: true)

        // Determine if enum should be stored as string
        val enumAsString = dbField?.enumAsString ?: annotations.any { ann ->
            if (ann.annotationClass.qualifiedName == "javax.persistence.Enumerated") {
                try {
                    val enumType = ann.annotationClass.java.getMethod("value").invoke(ann)
                    enumType?.toString() == "STRING"
                } catch (_: Exception) { false }
            } else false
        }

        // Determine if property type is a reference (entity type)
        // For generic type variables (T), classifier is null — use Any::class
        val propType = kProp.returnType.classifier as? KClass<*> ?: Any::class
        // Skip collection/map types — they are not DB columns (e.g. lazyDetails)
        if (Collection::class.java.isAssignableFrom(propType.java) || Map::class.java.isAssignableFrom(propType.java))
            return@mapNotNull null
        // Generic type variables (erased to Any) are not entity references
        val isGenericTypeVar = javaGetter?.genericReturnType is java.lang.reflect.TypeVariable<*>
        val isEnum = propType.java.isEnum
        val isReference = !isGenericTypeVar && !isEnum && !isScalarClass(propType)
                && propType != ByteArray::class && propType != CharArray::class

        // Build getter/setter — prefer Java getter/setter for interop with Java POJOs
        // (Kotlin reflection on Java classes with private fields fails with IllegalAccessException)
        val mutableProp = kProp as? kotlin.reflect.KMutableProperty1<T, *>
        // Resolve Java setter: try setXxx(), and for boolean "isFoo" properties try setFoo()
        val javaSetter = run {
            fun trySetters(name: String): java.lang.reflect.Method? =
                try { jClass.getMethod("set$name", propType.java) } catch (_: Exception) {
                    try { jClass.getMethod("set$name", propType.javaObjectType) } catch (_: Exception) { null }
                }
            trySetters(capitalizedName)
                ?: if (propName.startsWith("is") && propName.length > 2)
                    trySetters(propName.removePrefix("is"))
                else null
        }

        val getter: (T) -> Any? = if (javaGetter != null) {
            { entity: T -> javaGetter.invoke(entity) }
        } else {
            { entity: T -> kProp.get(entity) }
        }

        val setter: (T, Any?, Stormify) -> Unit = if (javaSetter != null) {
            { entity: T, value: Any?, stormify: Stormify ->
                javaSetter.invoke(entity, TypeUtils.castTo(propType, value, stormify))
            }
        } else if (mutableProp != null) {
            @Suppress("UNCHECKED_CAST")
            { entity: T, value: Any?, stormify: Stormify ->
                (mutableProp as kotlin.reflect.KMutableProperty1<T, Any?>).set(entity, TypeUtils.castTo(propType, value, stormify))
            }
        } else {
            { _: T, _: Any?, _: Stormify ->
                throw onl.ycode.kdbc.SQLException("Property ${kProp.name} is not mutable in ${type.simpleName}")
            }
        }

        PropertyMeta<T>(
            name = kProp.name,
            type = propType,
            isReference = isReference,
            getter = getter,
            setter = setter,
            dbNameOverride = dbNameOverride,
            isPrimaryKey = isPrimaryKey,
            sequence = sequence,
            isAutoIncrement = isAutoIncrement,
            isCreatable = isCreatable,
            isUpdatable = isUpdatable,
            isTransient = isTransient,
            isEnum = isEnum,
            enumAsString = enumAsString
        )
    }

    if (properties.isEmpty()) return null

    val constructor: () -> T = {
        try {
            val noArgCtor = jClass.getDeclaredConstructor()
            noArgCtor.isAccessible = true
            noArgCtor.newInstance()
        } catch (_: Exception) {
            // Try Kotlin default constructor (data classes with defaults)
            try {
                val ctor = jClass.kotlin.constructors.firstOrNull { c ->
                    c.parameters.all { it.isOptional }
                }
                ctor?.callBy(emptyMap())
                    ?: throw onl.ycode.kdbc.SQLException("No no-arg constructor for ${type.simpleName}")
            } catch (e: Exception) {
                throw onl.ycode.kdbc.SQLException("Cannot instantiate ${type.simpleName}: ${e.message}", e)
            }
        }
    }

    return EntityMeta(
        type = type,
        constructor = constructor,
        properties = properties,
        tableNameOverride = tableNameOverride
    )
}