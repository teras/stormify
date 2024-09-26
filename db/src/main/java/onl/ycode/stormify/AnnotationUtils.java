// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

class AnnotationUtils {
    static final Class<? extends Annotation> idClass;
    static final Class<? extends Annotation> transientClass;
    static final Class<? extends Annotation> tableClass;
    static final Class<? extends Annotation> columnClass;
    static final Class<? extends Annotation> joinColumnClass;
    static final Class<? extends Annotation> sequenceGeneratorClass;
    static final Method tableNameMethod;
    static final Method columnNameMethod;
    static final Method joinColumnNameMethod;
    static final Method sequenceGeneratorNameMethod;
    static final Method updateableColumnMethod;
    static final Method insertableColumnMethod;
    static final Method updatableJoinColumnMethod;
    static final Method insertableJoinColumnMethod;

    private AnnotationUtils() {
    }

    static {
        idClass = findClass("javax.persistence.Id");
        transientClass = findClass("javax.persistence.Transient");
        tableClass = findClass("javax.persistence.Table");
        columnClass = findClass("javax.persistence.Column");
        joinColumnClass = findClass("javax.persistence.JoinColumn");
        sequenceGeneratorClass = findClass("javax.persistence.SequenceGenerator");
        tableNameMethod = findMethod(tableClass, "name");
        columnNameMethod = findMethod(columnClass, "name");
        joinColumnNameMethod = findMethod(joinColumnClass, "name");
        sequenceGeneratorNameMethod = findMethod(sequenceGeneratorClass, "name");
        updateableColumnMethod = findMethod(columnClass, "updatable");
        insertableColumnMethod = findMethod(columnClass, "insertable");
        updatableJoinColumnMethod = findMethod(joinColumnClass, "updatable");
        insertableJoinColumnMethod = findMethod(joinColumnClass, "insertable");
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> findClass(String name) {
        try {
            return (Class<? extends Annotation>) Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Method findMethod(Class<? extends Annotation> annotationClass, String methodName) {
        try {
            return annotationClass != null ? annotationClass.getMethod(methodName) : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static boolean hasAnnotation(Class<?> clazz, Class<? extends Annotation> annotation) {
        if (annotation == null) return false;
        return clazz.getAnnotation(annotation) != null;
    }

    static boolean hasAnnotation(Field field, Class<? extends Annotation> annotation) {
        if (annotation == null) return false;
        return field.getAnnotation(annotation) != null;
    }

    @SuppressWarnings("unchecked")
    static <T> T getAnnotationValue(Class<?> clazz, Class<? extends Annotation> annotation, Method method) {
        if (annotation == null || method == null) return null;
        Annotation fieldAnnotation = clazz.getAnnotation(annotation);
        if (fieldAnnotation != null) {
            try {
                return (T) method.invoke(fieldAnnotation);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T> T getAnnotationValue(Field field, Class<? extends Annotation> annotation, Method method) {
        if (annotation == null || method == null) return null;
        Annotation fieldAnnotation = field.getAnnotation(annotation);
        if (fieldAnnotation != null) {
            try {
                return (T) method.invoke(fieldAnnotation);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static DbField getMethodAnnotation(Class<?> container, Method m, boolean alsoDelegate) {
        if (m == null)
            return null;
        DbField annotation = m.getAnnotation(DbField.class);
        if (annotation == null && alsoDelegate) try {
            annotation = container.getMethod(m.getName() + "$annotations", m.getParameterTypes()).getAnnotation(DbField.class);
        } catch (NoSuchMethodException ignored) {
            // Ignore
        }
        return annotation;
    }
}
