// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.sql.PreparedStatement;

interface QueryEnvironment<T> {
    T execute(PreparedStatement conn) throws Exception;
}
