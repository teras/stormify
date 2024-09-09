// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.lang.reflect.Method;

final class BeanInfo {
    String dbName;
    String propertyName;
    final String getterName;
    final Method getter;
    final Method setter;
    final Class<?> type;
    final DbField getterAnnotation;
    final DbField setterAnnotation;
    DbField fieldAnnotation;
    String sequence;
    boolean primaryByIdAnnotation;
    boolean updatable;
    boolean creatable;

    BeanInfo(String name, String getterName, Method getter, Method setter, Class<?> type, DbField getterAnnotation, DbField setterAnnotation, NamingPolicy policy) {
        this.propertyName = name;
        this.dbName = policy.convert(name);
        this.getterName = getterName;
        this.getter = getter;
        this.setter = setter;
        this.type = type;
        this.getterAnnotation = getterAnnotation;
        this.setterAnnotation = setterAnnotation;
        this.creatable = true;
        this.updatable = true;
    }
}
