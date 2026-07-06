package com.techdesksystem.techdesk.auth.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

final class TenantSchemaConnectionGuard implements InvocationHandler {

    private static final Set<String> CONNECTION_SQL_METHODS = Set.of(
            "prepareStatement", "prepareCall", "createStatement"
    );
    private static final Set<String> STATEMENT_EXECUTION_METHODS = Set.of(
            "execute", "executeQuery", "executeUpdate", "executeBatch",
            "executeLargeBatch", "executeLargeUpdate"
    );

    private final Connection delegate;
    private final String expectedTenant;
    private final TenantIsolationViolationReporter reporter;

    private TenantSchemaConnectionGuard(
            Connection delegate,
            String expectedTenant,
            TenantIsolationViolationReporter reporter
    ) {
        this.delegate = delegate;
        this.expectedTenant = expectedTenant;
        this.reporter = reporter;
    }

    static Connection protect(
            Connection connection,
            String expectedTenant,
            TenantIsolationViolationReporter reporter
    ) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new TenantSchemaConnectionGuard(
                        connection,
                        expectedTenant,
                        reporter
                )
        );
    }

    static Connection unwrap(Connection connection) {
        if (Proxy.isProxyClass(connection.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(connection);
            if (handler instanceof TenantSchemaConnectionGuard guard) {
                return guard.delegate;
            }
        }
        return connection;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments)
            throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return invokeDelegate(method, arguments);
        }
        if ("unwrap".equals(method.getName()) && arguments != null
                && arguments.length == 1
                && arguments[0] instanceof Class<?> type
                && type.isInstance(delegate)) {
            return type.cast(delegate);
        }
        if ("isWrapperFor".equals(method.getName()) && arguments != null
                && arguments.length == 1
                && arguments[0] instanceof Class<?> type) {
            return type.isInstance(delegate) || delegate.isWrapperFor(type);
        }
        if (CONNECTION_SQL_METHODS.contains(method.getName())) {
            String sql = firstSqlArgument(arguments);
            verifySchema(sql);
            Object statement = invokeDelegate(method, arguments);
            return guardStatement(statement, sql);
        }
        return invokeDelegate(method, arguments);
    }

    private Object guardStatement(Object statement, String preparedSql) {
        if (!(statement instanceof Statement sqlStatement)) {
            return statement;
        }

        Class<?> statementType = statement instanceof CallableStatement
                ? CallableStatement.class
                : statement instanceof PreparedStatement
                ? PreparedStatement.class
                : Statement.class;

        return Proxy.newProxyInstance(
                statementType.getClassLoader(),
                new Class<?>[]{statementType},
                (proxy, method, arguments) -> {
                    if (STATEMENT_EXECUTION_METHODS.contains(method.getName())) {
                        String executionSql = firstSqlArgument(arguments);
                        verifySchema(executionSql == null ? preparedSql : executionSql);
                    }
                    try {
                        return method.invoke(sqlStatement, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
    }

    private void verifySchema(String sql) throws Exception {
        String activeSchema = delegate.getSchema();
        String contextTenant = TenantContext.currentTenant().orElse(null);

        if (!expectedTenant.equals(activeSchema)
                || !expectedTenant.equals(contextTenant)) {
            TenantIsolationViolation violation = new TenantIsolationViolation(
                    "auth-service",
                    expectedTenant,
                    activeSchema,
                    "Connection schema or request context changed before SQL execution.",
                    fingerprint(sql),
                    Instant.now()
            );
            reporter.report(violation);
            throw new TenantIsolationException(
                    "Active database schema does not match the request tenant."
            );
        }
    }

    private Object invokeDelegate(Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private String firstSqlArgument(Object[] arguments) {
        return arguments != null && arguments.length > 0
                && arguments[0] instanceof String sql ? sql : null;
    }

    private String fingerprint(String sql) {
        if (sql == null) {
            return "unknown";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
