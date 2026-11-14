package cn.edu.ooad.rbac;

import cn.edu.ooad.rbac.infrastructure.persistence.EmployeeRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.OrganizationRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.RolePermissionRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.SimulatedRequestRepository;
import cn.edu.ooad.rbac.service.InitializationService;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C1-M1-02：在独立 MySQL 库验证运行时迁移与初始化。 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RBAC_DB_URL", matches = ".*[/]ooad_rbac0_test(?:[?].*)?")
class InitializationTest {
    @Autowired private InitializationService initialization;
    @Autowired private EmployeeRepository employees;
    @Autowired private OrganizationRepository organizations;
    @Autowired private RolePermissionRepository rolePermissions;
    @Autowired private SimulatedRequestRepository simulatedRequests;
    @TempDir private Path temporaryDirectory;

    /** 验证首次创建和重复运行，并通过测试事务回滚本用例生成的演示数据。 */
    @Test
    @Transactional
    @DisplayName("C1-M1-02 / C1-M1-05 启动迁移和重复演示初始化")
    void initializesDemoTwice() {
        assertThat(organizations.findByCode("demo-company-a")).as("专用测试库在用例前无演示数据").isEmpty();
        long employeesBefore = employees.count();
        long assignmentsBefore = rolePermissions.count();
        long requestsBefore = simulatedRequests.count();

        initialization.initializeDemo();
        long count = employees.count();
        long assignments = rolePermissions.count();
        assertThat(count).isEqualTo(employeesBefore + 4);
        assertThat(assignments).isEqualTo(assignmentsBefore + 1);
        assertThat(employees.findByUsername("super-admin")).isPresent();
        assertThat(simulatedRequests.count()).isEqualTo(requestsBefore + 24);

        initialization.initializeDemo();
        assertThat(employees.count()).isEqualTo(count);
        assertThat(rolePermissions.count()).isEqualTo(assignments);
        assertThat(simulatedRequests.count()).isEqualTo(requestsBefore + 24);
    }

    /** 后一张表发生内容冲突时，前面已写入的员工与组织也回滚。 */
    @Test
    @DisplayName("C1-M1-03 / C1-M1-04 三表重复导入和整批回滚")
    void importsAndRollsBack() throws Exception {
        writeFixtures(false);
        assertThat(initialization.importWorkbooks(temporaryDirectory).pending()).isEqualTo(1);
        long employeesBefore = employees.count();
        long organizationsBefore = organizations.count();
        initialization.importWorkbooks(temporaryDirectory);
        assertThat(employees.count()).isEqualTo(employeesBefore);
        assertThat(employees.findByUsername("m1-unmatched").orElseThrow().getOrganizationId()).isNull();
        assertThat(employees.findByUsername("m1-matched").orElseThrow().getOrganizationId()).isNotNull();

        writeFixtures(true);
        assertThatThrownBy(() -> initialization.importWorkbooks(temporaryDirectory))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("功能权限清单第 3 行");
        assertThat(employees.findByUsername("m1-rolled-back")).isEmpty();
        assertThat(employees.count()).isEqualTo(employeesBefore);
        assertThat(organizations.count()).isEqualTo(organizationsBefore);
    }

    /** 生成三张最小合成工作簿，附带匹配失败和后段冲突场景。 */
    private void writeFixtures(boolean conflict) throws Exception {
        write("市政公司员工信息表.xlsx", "员工信息", new String[][]{
                {"姓名", "用户名", "分公司", "部门", "岗位", "电话号码"},
                {"示例员工", "m1-matched", "集成测试公司", "工程部", "岗位", "00123"},
                {"示例员工", "m1-unmatched", "集成测试公司", "未知部门", null, null},
                conflict ? new String[]{"新增", "m1-rolled-back", "回滚测试公司", null, null, null} : null
        });
        write("市政公司部门信息表.xlsx", "部门信息", new String[][]{
                {"部门名称", "部门经理", "上级部门"},
                {"集成测试公司", "示例经理", null},
                {"集成测试公司-工程部", "示例经理", "集成测试公司"},
                conflict ? new String[]{"回滚测试公司", "示例经理", null} : null
        });
        write("市政公司应用软件功能权限清单(1).xlsx", "市政公司应用软件功能权限清单", new String[][]{
                {"功能清单"},
                {"软件系统", "功能模块", "功能点", "功能说明", "系统管理员", "公司领导", "部门经理", "项目经理", "普通员工"},
                {"集成测试系统", "测试模块", "测试功能", conflict ? "冲突说明" : "原说明", "全部", null, null, null, null}
        });
    }

    /** 写入工作簿，并保留空单元格以覆盖导入边界。 */
    private void write(String fileName, String sheetName, String[][] rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             java.io.OutputStream output = java.nio.file.Files.newOutputStream(temporaryDirectory.resolve(fileName))) {
            Sheet sheet = workbook.createSheet(sheetName);
            for (int index = 0; index < rows.length; index++) {
                String[] values = rows[index];
                if (values == null) {
                    continue;
                }
                Row row = sheet.createRow(index);
                for (int column = 0; column < values.length; column++) {
                    if (values[column] != null) {
                        row.createCell(column).setCellValue(values[column]);
                    }
                }
            }
            workbook.write(output);
        }
    }
}
