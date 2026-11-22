// 文件职责：验证独立账号校验的统一拒绝、密码计算成本及员工资格边界。
// 协作关系：两版共用；Session 生命周期及 HTTP 登录另由原回归验证。
package authentication;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import organization.Employee;
import organization.EmployeeRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountAuthenticationTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final LoginAccountRepository accounts = mock(LoginAccountRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);

    @Test
    void returnsVerifiedActiveEmployee() {
        Employee employee = employee();
        when(employees.findByUsername("account-test")).thenReturn(Optional.of(employee));
        when(accounts.findById(7L)).thenReturn(Optional.of(new LoginAccount(7L, "stored-hash")));
        when(passwords.matches("correct", "stored-hash")).thenReturn(true);
        assertThat(authenticator().verify("account-test", "correct")).isSameAs(employee);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "missing-account", "wrong-password", "disabled", "unmatched"})
    void rejectsCredentialsUniformlyAndAlwaysComputesPassword(String state) {
        Employee employee = employee();
        if (state.equals("disabled")) employee.disable();
        if (state.equals("unmatched")) ReflectionTestUtils.setField(employee, "organizationId", null);
        when(employees.findByUsername("account-test")).thenReturn(state.equals("unknown")
                ? Optional.empty() : Optional.of(employee));
        if (!state.equals("unknown")) {
            when(accounts.findById(7L)).thenReturn(state.equals("missing-account") ? Optional.empty()
                    : Optional.of(new LoginAccount(7L, "stored-hash")));
        }
        String expectedHash = state.equals("unknown") || state.equals("missing-account")
                ? "fallback-hash" : "stored-hash";
        when(passwords.matches("submitted", expectedHash)).thenReturn(!state.equals("wrong-password"));

        AccountAuthentication authentication = authenticator();
        assertThatThrownBy(() -> authentication.verify("account-test", "submitted"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("用户名或密码无效，或账号不可登录");
        verify(passwords).matches("submitted", expectedHash);
        if (state.equals("unknown")) verifyNoInteractions(accounts);
    }

    @Test
    void propagatesDatabaseFailureInsteadOfRejectingCredentials() {
        IllegalStateException failure = new IllegalStateException("test database unavailable");
        when(employees.findByUsername("account-test")).thenThrow(failure);
        AccountAuthentication authentication = authenticator();
        assertThatThrownBy(() -> authentication.verify("account-test", "submitted")).isSameAs(failure);
    }

    private AccountAuthentication authenticator() {
        when(passwords.encode(anyString())).thenReturn("fallback-hash");
        return new AccountAuthentication(employees, accounts, passwords);
    }

    private Employee employee() {
        Employee employee = new Employee("account-test", "测试员工", 2L, null, null, null, null);
        ReflectionTestUtils.setField(employee, "id", 7L);
        return employee;
    }
}
