// 文件职责：在独立临时测试库验证空库和V8升级到V9，不触碰应用库。
// 协作关系：读取各版classpath迁移；使用与真实后端回归相同的MySQL连接环境。
package contract;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import simulation.RequestOperation;

import static org.assertj.core.api.Assertions.assertThat;

/** 临时库名限定测试前缀，每次只清理本用例创建的单个库。 */
@EnabledIfEnvironmentVariable(named = "RBAC_DB_URL", matches = ".*[/]ooad_rbac1_(?:func|oo)_test(?:[?].*)?")
class ApiMigrationTest {
    @Test
    void migratesEmptyDatabase() throws Exception {
        verifyMigration(false);
    }

    @Test
    void upgradesExistingV8DataWithoutRegranting() throws Exception {
        verifyMigration(true);
    }

    private void verifyMigration(boolean existing) throws Exception {
        String source = System.getenv("RBAC_DB_URL");
        String user = System.getenv("RBAC_DB_USER");
        String password = System.getenv("RBAC_DB_PASSWORD");
        String edition = System.getProperty("rbac.test.edition");
        assertThat(source).contains("/ooad_rbac1_" + edition + "_test");
        String database = "ooad_rbac1_" + edition + "_api_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("ooad_rbac1_(func|oo)_api_test_[a-f0-9]{32}");
        String url = source.replaceFirst("/ooad_rbac1_(func|oo)_test(?=[?]|$)", "/" + database);
        try (Connection control = DriverManager.getConnection(source, user, password);
             Statement admin = control.createStatement()) {
            admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                if (existing) {
                    migrate(url, user, password, "8");
                    seedV8(url, user, password);
                }
                migrate(url, user, password, "9");
                try (Connection connection = DriverManager.getConnection(url, user, password);
                     Statement sql = connection.createStatement()) {
                    assertThat(count(sql, "SELECT COUNT(*) FROM api_operation")).isEqualTo(existing ? 32 : 0);
                    assertThat(count(sql, "SELECT COUNT(*) FROM api_operation_permission")).isEqualTo(existing ? 52 : 0);
                    if (existing) {
                        // V9只建立映射，原有撤权与停用策略必须保留。
                        assertThat(count(sql, "SELECT COUNT(*) FROM role_permission")).isZero();
                        assertThat(count(sql, "SELECT COUNT(*) FROM organization_permission WHERE status = 'DISABLED'")).isEqualTo(1);
                        assertThat(count(sql, """
                                SELECT COUNT(*) FROM api_operation_permission link
                                JOIN permission p ON p.id = link.permission_id WHERE link.system_id <> p.system_id
                                """)).isZero();
                    }
                }
                migrate(url, user, password, "9"); // Flyway重复执行不得重复写入。
            } finally {
                // database只来自上方固定前缀+UUID，绝不使用调用者提供的库名。
                admin.execute("DROP DATABASE `" + database + "`");
            }
        }
    }

    private void migrate(String url, String user, String password, String target) {
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                .target(target).load().migrate();
    }

    private long count(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }

    /** V8夹具含四个演示系统、已有权限和停用；未授权任何角色。 */
    private void seedV8(String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO organization (code, name, organization_type) VALUES ('migration-test', '迁移测试', 'COMPANY')");
            long owner = count(sql, "SELECT id FROM organization");
            int number = 0;
            for (String code : RequestOperation.DEMO_SYSTEMS) {
                sql.executeUpdate("INSERT INTO business_system (code, name, owner_organization_id, default_workspace) VALUES ('"
                        + code + "', '迁移系统', " + owner + ", " + (++number == 1) + ")");
            }
            // 与生产定义分开列出权限，能发现迁移漏配及跨系统串用。
            for (String code : java.util.List.of("read", "create", "update", "withdraw", "approve", "reject", "delete")) {
                sql.executeUpdate("INSERT INTO permission (code, name, system_id) SELECT 'request:" + code
                        + "', '测试权限', id FROM business_system");
            }
            sql.executeUpdate("INSERT INTO organization_permission (organization_id, permission_id, status) SELECT "
                    + owner + ", MIN(id), 'DISABLED' FROM permission");
        }
    }
}
