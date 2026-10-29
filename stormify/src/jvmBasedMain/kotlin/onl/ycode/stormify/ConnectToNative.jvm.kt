@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.stormify

import onl.ycode.kdbc.TypeConversion
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField

/** Maps to [java.math.BigInteger] on JVM. */
actual typealias NativeBigInteger = java.math.BigInteger

// kotlinx-datetime and ionspin are compileOnly on JVM; probe before referencing.
internal val supportsKotlinxDatetime = try {
    kotlinx.datetime.LocalDate::class.simpleName
    true
} catch (_: Throwable) {
    false
}

internal val supportsIonspinBigNumbers = try {
    com.ionspin.kotlin.bignum.decimal.BigDecimal::class.simpleName
    true
} catch (_: Throwable) {
    false
}

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

internal actual fun isBigDecimal(value: Any): Boolean =
    value is java.math.BigDecimal ||
            (supportsIonspinBigNumbers && value is com.ionspin.kotlin.bignum.decimal.BigDecimal)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
actual val Any.isOtherPrimitive: Boolean
    get() = this is java.util.Date ||
            this is java.time.temporal.Temporal ||
            this is java.util.UUID ||
            this is kotlin.uuid.Uuid
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
    // Table name override: @DbTable, then JPA @Table, then JPA @Entity (whose name is
    // also the JPA default table name). Same precedence as the annotation processor.
    val tableNameOverride = jClass.getAnnotation(DbTable::class.java)?.name?.takeIf { it.isNotBlank() }
        ?: jClass.annotations.jpa("Table")?.stringAttr("name")
        ?: jClass.annotations.jpa("Entity")?.stringAttr("name")

    val kProps = type.members.filterIsInstance<kotlin.reflect.KProperty1<T, *>>()
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
        // Where the value lives: the backing field, or for a delegated property the field
        // holding the delegate. A property with neither, such as `val x get() = ...` or a
        // `var` whose custom get and set use no field, stores nothing of its own, so it is
        // not a column. The annotation processor skips these properties too; how the two
        // paths treat properties that do have a field but no public setter is described
        // where the setter is chosen below.
        val storage = try { kProp.javaField } catch (_: Throwable) { jField }
            ?: return@mapNotNull null
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
        //    Every constructor that declares the parameter contributes, the primary one
        //    first: a secondary constructor repeating the parameter without annotations
        //    must not hide the annotations of the primary one.
        val ctorParamAnnotations: List<Annotation> = run {
            var cls: Class<*>? = jClass
            while (cls != null) {
                val kClass = cls.kotlin
                val ctors = listOfNotNull(kClass.primaryConstructor) +
                        kClass.constructors.filter { it != kClass.primaryConstructor }
                val params = ctors.mapNotNull { ctor -> ctor.parameters.find { it.name == propName } }
                if (params.isNotEmpty()) return@run params.flatMap { it.annotations }.distinct()
                cls = cls.superclass
            }
            emptyList()
        }
        val annotations = (jField?.annotations?.toList() ?: emptyList()) +
                (javaGetter?.annotations?.toList() ?: emptyList()) +
                (kProp.annotations) +
                ctorParamAnnotations

        // Check transient: Java keyword, Kotlin @Transient (on the backing field, or with
        // @delegate:Transient on the delegate field), JPA @Transient
        val isJavaTransient = java.lang.reflect.Modifier.isTransient(storage.modifiers)
        val isTransient = isJavaTransient || annotations.any {
            it is Transient || it.annotationClass.qualifiedName == "javax.persistence.Transient"
        }

        // Stormify and JPA annotations may be combined on one property. Every attribute
        // is merged the same way regardless of which annotations are present, so the
        // result matches the annotation processor: a name comes from the first source
        // that sets one (@DbField, @Column, @JoinColumn), a flag that defaults to false
        // is set by any source, and a flag that defaults to true is cleared by any source.
        val dbField = annotations.filterIsInstance<DbField>().firstOrNull()
        val jpaId = annotations.jpa("Id") != null
        val jpaColumn = annotations.jpa("Column")
        val jpaJoinColumn = annotations.jpa("JoinColumn")
        val jpaSequence = annotations.jpa("SequenceGenerator")

        val dbNameOverride = dbField?.name?.takeIf { it.isNotBlank() }
            ?: jpaColumn?.stringAttr("name")
            ?: jpaJoinColumn?.stringAttr("name")

        val isPrimaryKey = (dbField?.primaryKey ?: false) || jpaId
        // In JPA `name` names the generator and `sequenceName` the database sequence;
        // the generator name is the sequence only when `sequenceName` is left empty.
        val sequence = dbField?.primarySequence?.takeIf { it.isNotBlank() }
            ?: jpaSequence?.stringAttr("sequenceName")
            ?: jpaSequence?.stringAttr("name")

        val isAutoIncrement = (dbField?.autoIncrement ?: false) || annotations.any { ann ->
            if (ann.annotationClass.qualifiedName == "javax.persistence.GeneratedValue") {
                try {
                    val strategy = ann.annotationClass.java.getMethod("strategy").invoke(ann)
                    strategy?.toString() == "IDENTITY"
                } catch (_: Exception) { false }
            } else false
        }

        val isInsertable = (dbField?.insertable ?: true) &&
                jpaColumn?.booleanAttr("insertable") != false && jpaJoinColumn?.booleanAttr("insertable") != false
        val isUpdatable = (dbField?.updatable ?: true) &&
                jpaColumn?.booleanAttr("updatable") != false && jpaJoinColumn?.booleanAttr("updatable") != false

        // Determine if enum should be stored as string
        val enumAsString = (dbField?.enumAsString ?: false) || annotations.any { ann ->
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
        val isReference = !isGenericTypeVar && !isEnum && !TypeConversion.isKnownScalar(propType)
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

        // How a value read from the database is stored. A public setter (Java bean setter,
        // or Kotlin `var`, delegated or not) is used when there is one. Otherwise a property
        // with a backing field (a `val`, a `var` with a private or protected setter, a Java
        // field without setter, final or not) is loaded through that field, so it is never
        // left with a stale value that a later UPDATE would write back, and is read from it
        // for INSERT and UPDATE as well (see the getter below). A read-only property
        // whose field holds a delegate other than `db` (for example `by lazy`) has nowhere to
        // store the value: it cannot be a column, which is reported when the table metadata
        // is built, unless it is transient.
        // This loading through the field is specific to reflection: code generated by the
        // annotation processor cannot assign a field without a public setter, so that path
        // does not map such properties the same way.
        val publicKotlinSetter = mutableProp != null &&
                mutableProp.setter.visibility == kotlin.reflect.KVisibility.PUBLIC
        val isBackingField = storage.name == propName
        // A property loaded through its field (no public setter) is written through the same
        // field, so a custom getter cannot turn a load followed by an update into a change
        // of the stored value. Everything else is read through its getter.
        val fieldOnly = javaSetter == null && !publicKotlinSetter && isBackingField
        val getter: (T) -> Any? = if (fieldOnly) {
            storage.isAccessible = true
            { entity: T -> storage.get(entity) }
        } else if (javaGetter != null) {
            { entity: T -> javaGetter.invoke(entity) }
        } else {
            { entity: T -> kProp.get(entity) }
        }

        val readOnlyDelegate = javaSetter == null && !publicKotlinSetter && !isBackingField
        val delegateError = when {
            !readOnlyDelegate -> null
            // A db delegate stores the value, but only a public setter reaches it: `val x by db()`,
            // or `var x by db()` with a private or protected setter.
            db::class.java.isAssignableFrom(storage.type) ->
                if (mutableProp != null)
                    "Property ${kProp.name} of ${type.simpleName} has no public setter: make its setter public to be loaded"
                else
                    "Property ${kProp.name} of ${type.simpleName} is a val by db(): it must be a var to be loaded"
            else -> "Property ${kProp.name} of ${type.simpleName} uses a delegate and cannot be mapped to a column: " +
                    "mark it @delegate:Transient or use by db()"
        }

        val setter: (T, Any?, Stormify) -> Unit = if (javaSetter != null) {
            { entity: T, value: Any?, stormify: Stormify ->
                javaSetter.invoke(entity, TypeUtils.castTo(propType, value, stormify))
            }
        } else if (publicKotlinSetter) {
            @Suppress("UNCHECKED_CAST")
            { entity: T, value: Any?, stormify: Stormify ->
                (mutableProp as kotlin.reflect.KMutableProperty1<T, Any?>).set(entity, TypeUtils.castTo(propType, value, stormify))
            }
        } else if (isBackingField) {
            storage.isAccessible = true
            { entity: T, value: Any?, stormify: Stormify ->
                storage.set(entity, TypeUtils.castTo(propType, value, stormify))
            }
        } else {
            { _: T, _: Any?, _: Stormify ->
                throw onl.ycode.kdbc.SQLException(delegateError ?: "Property ${kProp.name} is not mutable in ${type.simpleName}")
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
            isInsertable = isInsertable,
            isUpdatable = isUpdatable,
            isTransient = isTransient,
            isEnum = isEnum,
            enumAsString = enumAsString
        ).also { it.mappingError = delegateError }
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

private fun Array<Annotation>.jpa(simpleName: String): Annotation? = asList().jpa(simpleName)

private fun List<Annotation>.jpa(simpleName: String): Annotation? =
    find { it.annotationClass.qualifiedName == "javax.persistence.$simpleName" }

private fun Annotation.attr(name: String): Any? =
    try { annotationClass.java.getMethod(name).invoke(this) } catch (_: Exception) { null }

/** A string attribute of a JPA annotation, or null when it is missing or blank (the JPA "unset" value). */
private fun Annotation.stringAttr(name: String): String? = (attr(name) as? String)?.takeIf { it.isNotBlank() }

private fun Annotation.booleanAttr(name: String): Boolean? = attr(name) as? Boolean
