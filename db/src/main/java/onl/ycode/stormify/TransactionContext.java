// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static onl.ycode.stormify.StormifyManager.stormify;
import static onl.ycode.stormify.TypeUtils.getOrThrow;

class TransactionContext implements Closeable {
    private static final ThreadLocal<TransactionContext> threadLocal = new ThreadLocal<>();
    private static final AtomicLong counter = new AtomicLong();

    private final List<Savepoint> savepoints = new ArrayList<>();
    private final Connection connection;

    static TransactionContext begin() throws SQLException {
        TransactionContext mgr = threadLocal.get();
        if (mgr == null) {
            // New transaction
            mgr = new TransactionContext();
            threadLocal.set(mgr);
            stormify().dbLog("Start transaction", null);
            return mgr;
        }
        // Nested transaction
        String sp = "stormify_" + System.currentTimeMillis() + "_" + counter.incrementAndGet();
        mgr.savepoints.add(mgr.connection.setSavepoint(sp));
        stormify().dbLog("Start inner transaction #" + mgr.savepoints.size(), null);
        return mgr;
    }

    TransactionContext() {
        try {
            connection = getOrThrow(() -> stormify().getDataSource().getConnection(), () -> "No connection found");
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new QueryException("Unable to initialize connection", e);
        }
    }

    void commit() {
        if (!savepoints.isEmpty()) {
            stormify().dbLog("Commit inner transaction #" + savepoints.size(), null);
            try {
                connection.releaseSavepoint(savepoints.get(savepoints.size() - 1));
            } catch (SQLException e) {
                throw new QueryException("Unable to release savepoint", e);
            }
        } else {
            stormify().dbLog("Commit transaction", null);
            try {
                connection.commit();
            } catch (SQLException e) {
                throw new QueryException("Unable to commit transaction", e);
            }
        }
    }

    void failed() {
        if (!savepoints.isEmpty()) {
            stormify().dbLog("Rollback inner transaction #" + savepoints.size(), null);
            try {
                connection.rollback(savepoints.get(savepoints.size() - 1));
            } catch (SQLException e) {
                throw new QueryException("Unable to rollback to savepoint", e);
            }
        } else {
            stormify().dbLog("Rollback transaction", null);
            try {
                connection.rollback();
            } catch (SQLException e) {
                throw new QueryException("Unable to rollback transaction", e);
            }
        }
    }


    @Override
    public void close() {
        if (!savepoints.isEmpty())
            savepoints.remove(savepoints.size() - 1);
        else {
            threadLocal.remove();
            try {
                connection.setAutoCommit(true);
                connection.close();
            } catch (SQLException e) {
                throw new QueryException("Unable to close connection", e);
            }
        }
    }

    static TransactionalConnection getConnection() throws SQLException {
        TransactionContext mgr = threadLocal.get();
        if (mgr == null)
            return new TransactionalConnection(stormify().getDataSource().getConnection(), false);
        if (mgr.connection == null)
            throw new QueryException("Unable to initialize connection");
        return new TransactionalConnection(mgr.connection, true);
    }
}
