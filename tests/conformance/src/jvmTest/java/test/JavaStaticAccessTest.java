// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test;

import onl.ycode.stormify.NamingPolicy;
import onl.ycode.stormify.StormifyLifecycle;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The naming presets and the lifecycle reset are reached from Java as the docs write them. */
public class JavaStaticAccessTest {
    @Test
    public void namingPresetsAreStaticFields() {
        assertEquals("userName", NamingPolicy.CAMEL_CASE.invoke("userName"));
        assertEquals("user_name", NamingPolicy.LOWER_CASE_WITH_UNDERSCORES.invoke("userName"));
        assertEquals("USER_NAME", NamingPolicy.UPPER_CASE_WITH_UNDERSCORES.invoke("userName"));
    }

    @Test
    public void lifecycleClearIsStatic() {
        StormifyLifecycle.clear();
    }
}
