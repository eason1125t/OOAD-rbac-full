// 文件职责：验证 Spring JWT Provider 到可信员工 ID 的转换及强制登录状态校验。
// 协作关系：真实验签；登录状态和员工仓储使用可控替身，Redis/HTTP 留到后续集成验收。
package authentication;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.test.util.ReflectionTestUtils;
import organization.Employee;
import organization.EmployeeRepository;

import static authentication.JwtFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtEmployeeAuthenticationTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final JwtEmployeeAuthentication.LoginVerifier logins = mock(JwtEmployeeAuthentication.LoginVerifier.class);
    private final JwtAuthenticationProvider provider = provider();

    @Test
    void restoresLongPrincipalAndDoesNotTrustRoleClaims() {
        when(employees.findById(7L)).thenReturn(Optional.of(employee()));
        Map<String,Object> claims = claims();
        claims.put("scope", "admin");
        claims.put("roles", java.util.List.of("GLOBAL_ADMIN"));
        Authentication result = provider.authenticate(new BearerTokenAuthenticationToken(sign(claims)));
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo(7L);
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getAuthorities()).isEmpty();
        verify(logins).requireActive(SID, 7L);
        verify(employees).findById(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "unmatched"})
    void rejectsLatestIneligibleEmployee(String state) {
        Employee employee = employee();
        if (state.equals("disabled")) employee.disable();
        if (state.equals("unmatched")) ReflectionTestUtils.setField(employee,"organizationId",null);
        when(employees.findById(7L)).thenReturn(state.equals("missing") ? Optional.empty() : Optional.of(employee));
        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(sign(claims()))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verify(logins).rejectEmployee(SID, 7L);
    }

    @Test
    void checksLoginBeforeEmployeeAndRejectsRevokedLogin() {
        doThrow(new OAuth2AuthenticationException("invalid_token")).when(logins).requireActive(SID, 7L);
        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(sign(claims()))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(employees);
    }

    @Test
    void propagatesLoginStorageFailureInsteadOfAcceptingSignature() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("login store unavailable");
        doThrow(failure).when(logins).requireActive(SID, 7L);
        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(sign(claims())))).isSameAs(failure);
        verifyNoInteractions(employees);
    }

    @Test
    void propagatesEmployeeDatabaseFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("employee store unavailable");
        when(employees.findById(7L)).thenThrow(failure);
        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(sign(claims())))).isSameAs(failure);
    }

    @Test
    void rejectsInvalidSignatureBeforeAnyStorageAccess() {
        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(sign(claims(),keys(),
                com.nimbusds.jose.JWSAlgorithm.RS256)))).isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(logins, employees);
    }

    @Test
    void cannotConstructWithoutLoginVerifier() {
        assertThatThrownBy(() -> new JwtEmployeeAuthentication(new CurrentEmployeeService(employees), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JwtAuthenticationProvider provider() {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(tokens(CLOCK));
        provider.setJwtAuthenticationConverter(new JwtEmployeeAuthentication(new CurrentEmployeeService(employees), logins));
        return provider;
    }

    private Employee employee() {
        Employee employee = new Employee("jwt-test", "JWT测试员工", 2L, null, null, null, null);
        ReflectionTestUtils.setField(employee, "id", 7L);
        return employee;
    }
}
