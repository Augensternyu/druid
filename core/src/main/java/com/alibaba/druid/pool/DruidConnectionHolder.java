/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.pool;

import com.alibaba.druid.DbType;
import com.alibaba.druid.filter.FilterChainImpl;
import com.alibaba.druid.pool.DruidAbstractDataSource.PhysicalConnectionInfo;
import com.alibaba.druid.proxy.jdbc.WrapperProxy;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.JdbcUtils;
import com.alibaba.druid.util.Utils;

import javax.sql.ConnectionEventListener;
import javax.sql.StatementEventListener;

import java.lang.reflect.Field;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author wenshao [szujobs@hotmail.com]
 */
public final class DruidConnectionHolder {
    private static final Log LOG = LogFactory.getLog(DruidConnectionHolder.class);

    static volatile boolean ORACLE_SOCKET_FIELD_ERROR;
    static volatile Field ORACLE_FIELD_NET;
    static volatile Field ORACLE_FIELD_S_ATTS;
    static volatile Field ORACLE_FIELD_NT;
    static volatile Field ORACLE_FIELD_SOCKET;

    public static boolean holdabilityUnsupported;

    protected final DruidAbstractDataSource dataSource;
    protected final long connectionId;
    protected final Connection conn;
    protected final List<ConnectionEventListener> connectionEventListeners = new CopyOnWriteArrayList<ConnectionEventListener>();
    protected final List<StatementEventListener> statementEventListeners = new CopyOnWriteArrayList<StatementEventListener>();
    protected final long connectTimeMillis;
    protected volatile long lastActiveTimeMillis;
    protected volatile long lastExecTimeMillis;
    protected volatile long lastKeepTimeMillis;
    protected volatile long lastValidTimeMillis;
    protected long useCount;
    private long keepAliveCheckCount;
    private long lastNotEmptyWaitNanos;
    private final long createNanoSpan;
    protected PreparedStatementPool statementPool;
    protected final List<Statement> statementTrace = new ArrayList<Statement>(2);
    protected final boolean defaultReadOnly;
    protected final int defaultHoldability;
    protected final int defaultTransactionIsolation;
    protected final boolean defaultAutoCommit;
    protected boolean underlyingReadOnly;
    protected int underlyingHoldability;
    protected int underlyingTransactionIsolation;
    protected boolean underlyingAutoCommit;
    protected volatile boolean discard;
    protected volatile boolean active;
    protected final Map<String, Object> variables;
    protected final Map<String, Object> globalVariables;
    final ReentrantLock lock = new ReentrantLock();
    protected String initSchema;
    protected Socket socket;
    protected final long userPasswordVersion;

    volatile FilterChainImpl filterChain;

    public DruidConnectionHolder(DruidAbstractDataSource dataSource, PhysicalConnectionInfo pyConnectInfo)
            throws SQLException {
        this(
                dataSource,
                pyConnectInfo.getPhysicalConnection(),
                pyConnectInfo.getConnectNanoSpan(),
                pyConnectInfo.getVairiables(),
                pyConnectInfo.getGlobalVairiables()
        );
    }

    public DruidConnectionHolder(DruidAbstractDataSource dataSource, Connection conn, long connectNanoSpan)
            throws SQLException {
        this(dataSource, conn, connectNanoSpan, null, null);
    }

    public DruidConnectionHolder(
            DruidAbstractDataSource dataSource,
            Connection conn,
            long connectNanoSpan,
            Map<String, Object> variables,
            Map<String, Object> globalVariables
    ) throws SQLException {
        this.dataSource = dataSource;
        this.conn = conn;
        this.createNanoSpan = connectNanoSpan;
        this.variables = variables;
        this.globalVariables = globalVariables;
        this.userPasswordVersion = dataSource.getUserPasswordVersion();

        this.connectTimeMillis = System.currentTimeMillis();
        this.lastActiveTimeMillis = connectTimeMillis;
        this.lastExecTimeMillis = connectTimeMillis;

        this.underlyingAutoCommit = conn.getAutoCommit();

        if (conn instanceof WrapperProxy) {
            this.connectionId = ((WrapperProxy) conn).getId();
        } else {
            this.connectionId = dataSource.createConnectionId();
        }

        Class<? extends Connection> conClass = conn.getClass();
        String connClassName = conClass.getName();
        if ((!ORACLE_SOCKET_FIELD_ERROR) && connClassName.equals("oracle.jdbc.driver.T4CConnection")) {
            try {
                if (ORACLE_FIELD_NET == null) {
                    Field field = conClass.getDeclaredField("net");
                    field.setAccessible(true);
                    ORACLE_FIELD_NET = field;
                }
                Object net = ORACLE_FIELD_NET.get(conn);

                if (ORACLE_FIELD_S_ATTS == null) {
                    // NSProtocol
                    Field field = net.getClass().getSuperclass().getDeclaredField("sAtts");
                    field.setAccessible(true);
                    ORACLE_FIELD_S_ATTS = field;
                }

                Object sAtts = ORACLE_FIELD_S_ATTS.get(net);

                if (ORACLE_FIELD_NT == null) {
                    Field field = sAtts.getClass().getDeclaredField("nt");
                    field.setAccessible(true);
                    ORACLE_FIELD_NT = field;
                }

                Object nt = ORACLE_FIELD_NT.get(sAtts);

                if (ORACLE_FIELD_SOCKET == null) {
                    Field field = nt.getClass().getDeclaredField("socket");
                    field.setAccessible(true);
                    ORACLE_FIELD_SOCKET = field;
                }

                socket = (Socket) ORACLE_FIELD_SOCKET.get(nt);
            } catch (Throwable ignored) {
                ORACLE_SOCKET_FIELD_ERROR = true;
                // ignored
            }
        }

        {
            boolean initUnderlyHoldability = !holdabilityUnsupported;
            DbType dbType = DbType.of(dataSource.dbTypeName);
            if (dbType == DbType.sybase //
                    || dbType == DbType.db2 //
                    || dbType == DbType.hive //
                    || dbType == DbType.odps //
            ) {
                initUnderlyHoldability = false;
            }
            if (initUnderlyHoldability) {
                try {
                    this.underlyingHoldability = conn.getHoldability();
                } catch (UnsupportedOperationException e) {
                    holdabilityUnsupported = true;
                    LOG.warn("getHoldability unsupported", e);
                } catch (SQLFeatureNotSupportedException e) {
                    holdabilityUnsupported = true;
                    LOG.warn("getHoldability unsupported", e);
                } catch (SQLException e) {
                    // bug fixed for hive jdbc-driver
                    if ("Method not supported".equals(e.getMessage())) {
                        holdabilityUnsupported = true;
                    }
                    LOG.warn("getHoldability error", e);
                }
            }
        }

        this.underlyingReadOnly = conn.isReadOnly();
        try {
            this.underlyingTransactionIsolation = conn.getTransactionIsolation();
        } catch (SQLException e) {
            // compartible for alibaba corba
            if ("HY000".equals(e.getSQLState())
                    || "com.mysql.jdbc.exceptions.jdbc4.MySQLSyntaxErrorException".equals(e.getClass().getName())) {
                // skip
            } else {
                throw e;
            }
        }

        this.defaultHoldability = underlyingHoldability;
        this.defaultTransactionIsolation = underlyingTransactionIsolation;
        this.defaultAutoCommit = underlyingAutoCommit;
        this.defaultReadOnly = underlyingReadOnly;
    }

    protected FilterChainImpl createChain() {
        FilterChainImpl chain = this.filterChain;
        if (chain == null) {
            chain = new FilterChainImpl(dataSource);
        } else {
            this.filterChain = null;
        }

        return chain;
    }

    protected void recycleFilterChain(FilterChainImpl chain) {
        chain.reset();
        this.filterChain = chain;
    }

    public long getConnectTimeMillis() {
        return connectTimeMillis;
    }

    public boolean isUnderlyingReadOnly() {
        return underlyingReadOnly;
    }

    public void setUnderlyingReadOnly(boolean underlyingReadOnly) {
        this.underlyingReadOnly = underlyingReadOnly;
    }

    public int getUnderlyingHoldability() {
        return underlyingHoldability;
    }

    public void setUnderlyingHoldability(int underlyingHoldability) {
        this.underlyingHoldability = underlyingHoldability;
    }

    public int getUnderlyingTransactionIsolation() {
        return underlyingTransactionIsolation;
    }

    public void setUnderlyingTransactionIsolation(int underlyingTransactionIsolation) {
        this.underlyingTransactionIsolation = underlyingTransactionIsolation;
    }

    public boolean isUnderlyingAutoCommit() {
        return underlyingAutoCommit;
    }

    public void setUnderlyingAutoCommit(boolean underlyingAutoCommit) {
        this.underlyingAutoCommit = underlyingAutoCommit;
    }

    public long getLastActiveTimeMillis() {
        return lastActiveTimeMillis;
    }

    public void setLastActiveTimeMillis(long lastActiveMillis) {
        this.lastActiveTimeMillis = lastActiveMillis;
    }

    public long getLastExecTimeMillis() {
        return lastExecTimeMillis;
    }

    public void setLastExecTimeMillis(long lastExecTimeMillis) {
        this.lastExecTimeMillis = lastExecTimeMillis;
    }

    public void addTrace(DruidPooledStatement stmt) {
        lock.lock();
        try {
            statementTrace.add(stmt);
        } finally {
            lock.unlock();
        }
    }

    public void removeTrace(DruidPooledStatement stmt) {
        lock.lock();
        try {
            statementTrace.remove(stmt);
        } finally {
            lock.unlock();
        }
    }

    public List<ConnectionEventListener> getConnectionEventListeners() {
        return connectionEventListeners;
    }

    public List<StatementEventListener> getStatementEventListeners() {
        return statementEventListeners;
    }

    public PreparedStatementPool getStatementPool() {
        if (statementPool == null) {
            statementPool = new PreparedStatementPool(this);
        }
        return statementPool;
    }

    public PreparedStatementPool getStatementPoolDirect() {
        return statementPool;
    }

    public void clearStatementCache() {
        if (this.statementPool == null) {
            return;
        }
        this.statementPool.clear();
    }

    public DruidAbstractDataSource getDataSource() {
        return dataSource;
    }

    public boolean isPoolPreparedStatements() {
        return dataSource.isPoolPreparedStatements();
    }

    public Connection getConnection() {
        return conn;
    }

    public long getTimeMillis() {
        return connectTimeMillis;
    }

    public long getUseCount() {
        return useCount;
    }

    public long getConnectionId() {
        return connectionId;
    }

    public void incrementUseCount() {
        useCount++;
    }

    public long getKeepAliveCheckCount() {
        return keepAliveCheckCount;
    }

    public void incrementKeepAliveCheckCount() {
        keepAliveCheckCount++;
    }

    public void reset() throws SQLException {
        // reset default settings
        if (underlyingReadOnly != defaultReadOnly) {
            conn.setReadOnly(defaultReadOnly);
            underlyingReadOnly = defaultReadOnly;
        }

        if (underlyingHoldability != defaultHoldability) {
            conn.setHoldability(defaultHoldability);
            underlyingHoldability = defaultHoldability;
        }

        if (!dataSource.isKeepConnectionUnderlyingTransactionIsolation()
                && underlyingTransactionIsolation != defaultTransactionIsolation) {
            conn.setTransactionIsolation(defaultTransactionIsolation);
            underlyingTransactionIsolation = defaultTransactionIsolation;
        }

        if (underlyingAutoCommit != defaultAutoCommit) {
            conn.setAutoCommit(defaultAutoCommit);
            underlyingAutoCommit = defaultAutoCommit;
        }

        if (!connectionEventListeners.isEmpty()) {
            connectionEventListeners.clear();
        }
        if (!statementEventListeners.isEmpty()) {
            statementEventListeners.clear();
        }

        lock.lock();
        try {
            if (!statementTrace.isEmpty()) {
                Object[] items = statementTrace.toArray();
                for (int i = 0; i < items.length; i++) {
                    Object item = items[i];
                    Statement stmt = (Statement) item;
                    JdbcUtils.close(stmt);
                }

                statementTrace.clear();
            }
        } finally {
            lock.unlock();
        }

        conn.clearWarnings();
    }

    public boolean isDiscard() {
        return discard;
    }

    public void setDiscard(boolean discard) {
        this.discard = discard;
    }

    public long getCreateNanoSpan() {
        return createNanoSpan;
    }

    public long getLastNotEmptyWaitNanos() {
        return lastNotEmptyWaitNanos;
    }

    protected void setLastNotEmptyWaitNanos(long lastNotEmptyWaitNanos) {
        this.lastNotEmptyWaitNanos = lastNotEmptyWaitNanos;
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();

        buf.append("{ID:");
        buf.append(System.identityHashCode(conn));
        buf.append(", ConnectTime:\"");
        buf.append(Utils.toString(new Date(this.connectTimeMillis)));

        buf.append("\", UseCount:");
        buf.append(useCount);

        if (lastActiveTimeMillis > 0) {
            buf.append(", LastActiveTime:\"");
            buf.append(Utils.toString(new Date(lastActiveTimeMillis)));
            buf.append("\"");
        }

        if (lastKeepTimeMillis > 0) {
            buf.append(", LastKeepTimeMillis:\"");
            buf.append(Utils.toString(new Date(lastKeepTimeMillis)));
            buf.append("\"");
        }

        if (statementPool != null && statementPool.getMap().size() > 0) {
            buf.append("\", CachedStatementCount:");
            buf.append(statementPool.getMap().size());
        }

        buf.append("}");

        return buf.toString();
    }

    public long getUserPasswordVersion() {
        return userPasswordVersion;
    }
}
