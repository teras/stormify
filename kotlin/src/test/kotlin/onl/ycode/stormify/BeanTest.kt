// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify

import onl.ycode.stormify.StormifyManager.stormify
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals

private class MyBean {
    var extra: Boolean = true

    @DbField(name = "---plain")
    var other: Boolean = true

    @set:DbField(name = "--set")
    var isExtra: Boolean? = null

    @get:DbField(name = "---get")
    var a1: String? = null

    @field:DbField(name = "---get")
    var a2: String? = null

    @DbField(name = "---#plain")
    val a3: Boolean by db(false)

    @set:DbField(name = "--#set")
    var a4: Boolean by db(false)

    @get:DbField(name = "---#get")
    var a5: String? by db("")

    @delegate:DbField(name = "---delegate")
    var a6: String? by db("")

    @DbField(name = "---#\$get")
    fun getMoney() = 100

    @DbField(name = "---#\$set")
    fun setMoney(value: Int) {
    }
}

class BeanTest {
    val logger = TestLogger()

    @AfterTest
    fun cleanup() {
        logger.close()
    }

    @Test
    fun test() {
        val wrong = stormify().getTableInfo(MyBean::class.java)
        wrong.checkConsistency()

        val tableInfo = stormify().getTableInfo(MyBean::class.java)
        assertEquals(
            "my_bean{\uD83C\uDFF7{\uD83D\uDD04money \uD83D\uDCBE---#\$get : int} \uD83C\uDFF7{\uD83D\uDD04isExtra \uD83D\uDCBE--set : Boolean} \uD83C\uDFF7{\uD83D\uDD04a1 \uD83D\uDCBE---get : String} \uD83C\uDFF7{\uD83D\uDD04a2 \uD83D\uDCBE---get : String} \uD83C\uDFF7{\uD83D\uDCE4a3 \uD83D\uDCBE---#plain : boolean} \uD83C\uDFF7{\uD83D\uDD04a4 \uD83D\uDCBE--#set : boolean} \uD83C\uDFF7{\uD83D\uDD04a5 \uD83D\uDCBE---#get : String} \uD83C\uDFF7{\uD83D\uDD04a6 \uD83D\uDCBE---delegate : String} \uD83C\uDFF7{\uD83D\uDD04extra : boolean} \uD83C\uDFF7{\uD83D\uDD04other \uD83D\uDCBE---plain : boolean}}",
            tableInfo.toString(),
            "Table info does not match"
        )
        assertEquals(
            """Table my_bean has more than one field that is creatable for column ---get: [{🔄a1 💾---get : String}, {🔄a2 💾---get : String}]
Table my_bean has more than one field that is updatable for column ---get : [{🔄a1 💾---get : String}, {🔄a2 💾---get : String}]
Please use the appropriate annotations to mark fields as non-creatable or non-updatable.
The relevant annotations are @onl.ycode.stormify.DbField,  @javax.persistence.Column, and @javax.persistence.JoinColumn
""", logger()
        )

    }
}