// 文件职责：验证可信员工读取的有效性、实时状态和存储故障边界。
// 协作关系：由两版 Maven 执行同一组测试，HTTP 适配和会话回归另由 AuthSessionTest 验证。
package authentication;

import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import organization.Employee;
import organization.EmployeeRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 不依赖 Session 或 SecurityContext 的员工读取行为，保持两版一致。 */
class CurrentEmployeeServiceTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final CurrentEmployeeService current = new CurrentEmployeeService(employees);

    @Test
    @DisplayName("ID-S2-01 根据可信ID返回启用且有组织归属的最新员工")
    void returnsActiveEmployee() {
        Employee employee = employee(2L);
        when(employees.findById(7L)).thenReturn(Optional.of(employee));

        assertThat(current.findActive(7L)).containsSame(employee);
        assertThat(current.requireActive(7L)).isSameAs(employee);
    }

    @Test
    @DisplayName("ID-S2-02 缺少可信ID时拒绝身份且不查询数据库")
    void rejectsMissingPrincipal() {
        assertThat(current.findActive(null)).isEmpty();
        assertAuthenticationRequired(null);
        verifyNoInteractions(employees);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "unmatched"})
    @DisplayName("ID-S2-03 员工不存在、停用或组织未匹配统一失去身份资格")
    void rejectsIneligibleEmployee(String state) {
        Employee employee = employee("unmatched".equals(state) ? null : 2L);
        if ("disabled".equals(state)) {
            employee.disable();
        }
        when(employees.findById(7L)).thenReturn("missing".equals(state)
                ? Optional.empty() : Optional.of(employee));

        assertThat(current.findActive(7L)).isEmpty();
        assertAuthenticationRequired(7L);
    }

    @Test
    @DisplayName("ID-S2-04 组织调整及停用后再次读取不沿用旧身份状态")
    void refreshesEmployeeState() {
        Employee first = employee(2L);
        Employee moved = employee(3L);
        Employee disabled = employee(3L);
        disabled.disable();
        when(employees.findById(7L)).thenReturn(Optional.of(first), Optional.of(moved), Optional.of(disabled));

        assertThat(current.requireActive(7L).getOrganizationId()).isEqualTo(2L);
        assertThat(current.requireActive(7L).getOrganizationId()).isEqualTo(3L);
        assertAuthenticationRequired(7L);
    }

    @Test
    @DisplayName("ID-S2-05 存储故障继续传播供安全边界映射503")
    void propagatesStorageFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("test unavailable");
        when(employees.findById(7L)).thenThrow(failure);

        assertThatThrownBy(() -> current.findActive(7L)).isSameAs(failure);
        assertThatThrownBy(() -> current.requireActive(7L)).isSameAs(failure);
    }

    @Test
    @DisplayName("ID-S2-06 空员工不具备登录资格，资格检查不执行认证或数据库访问")
    void treatsNullEmployeeAsIneligible() {
        assertThat(CurrentEmployeeService.canSignIn(null)).isFalse();
        verifyNoInteractions(employees);
    }

    /** 独立期望验证稳定错误码与消息，而非复用被测条件生成预期。 */
    private void assertAuthenticationRequired(Long employeeId) {
        assertThatThrownBy(() -> current.requireActive(employeeId))
                .isInstanceOfSatisfying(BusinessAccessException.class, failure -> {
                    assertThat(failure.getReason()).isEqualTo(Reason.AUTHENTICATION_REQUIRED);
                    assertThat(failure.getMessage()).isEqualTo("请登录后重试");
                });
    }

    /** 仓储夹具使用相同ID，不通过用户名或外部请求选择身份。 */
    private Employee employee(Long organizationId) {
        Employee employee = new Employee("identity-test", "测试员工", organizationId, null, null, null, null);
        ReflectionTestUtils.setField(employee, "id", 7L);
        return employee;
    }
}
