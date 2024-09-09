// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.sql.Connection;
import java.sql.SQLException;

class TransactionalConnection implements AutoCloseable {
    private final Connection connection;
    private final boolean inTransaction;

    TransactionalConnection(Connection connection, boolean inTransaction) {
        this.connection = connection;
        this.inTransaction = inTransaction;
    }

    Connection get() {
        return connection;
    }

    @Override
    public void close() throws Exception {
        if (!inTransaction) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new QueryException("Unable to close connection", e);
            }
        }
    }
}
