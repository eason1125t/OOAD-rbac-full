// 文件职责：验证刷新在签名前跨越绝对期限及签名失败时的拒绝/消费边界。
// 协作关系：使用真实签发器和可移动时钟验证到期，存储替身只标明副作用是否发生。
package authentication;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import organization.Employee;

import static authentication.JwtFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class LoginAuthenticationTest {
    private final RedisLoginStore store = mock(RedisLoginStore.class);
    private final CurrentEmployeeService employees = mock(CurrentEmployeeService.class);
    private final RefreshCredential credential = RefreshCredential.create(SID);
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void expiryBetweenEmployeeCheckAndSigningIs401WithoutRotation() {
        RedisLoginStore.Login login = new RedisLoginStore.Login(SID, 7L, NOW, NOW.plusSeconds(1));
        when(store.inspect(credential)).thenReturn(login);
        when(employees.findActive(7L)).thenAnswer(invocation -> {
            now.set(NOW.plusSeconds(2));
            return Optional.of(employee());
        });
        JwtAccessTokens tokens = new JwtAccessTokens(ISSUER, AUDIENCE, (RSAPublicKey)KEYS.getPublic(),
                (RSAPrivateKey)KEYS.getPrivate(), clock, Duration.ofMinutes(15));
        assertThatThrownBy(() -> service(tokens).refresh(credential.value()))
                .isInstanceOfSatisfying(AuthenticationFailure.class,
                        failure -> assertThat(failure.getError().getErrorCode()).isEqualTo("REFRESH_TOKEN_INVALID"));
        verify(store).revoke(credential);
        verify(store, never()).rotate(any(), any(), any());
    }

    @Test
    void signingFailureDoesNotConsumeCurrentRefreshCredential() {
        RedisLoginStore.Login login = new RedisLoginStore.Login(SID,7L,NOW,NOW.plusSeconds(3600));
        when(store.inspect(credential)).thenReturn(login);
        when(employees.findActive(7L)).thenReturn(Optional.of(employee()));
        JwtAccessTokens tokens = mock(JwtAccessTokens.class);
        JwtEncodingException failure = new JwtEncodingException("injected signing failure");
        when(tokens.issue(anyLong(),anyString(),any())).thenThrow(failure);
        assertThatThrownBy(() -> service(tokens).refresh(credential.value())).isSameAs(failure);
        verify(store,never()).rotate(any(),any(),any());
        verify(store,never()).revoke(any());
    }

    private LoginAuthentication service(JwtAccessTokens tokens) {
        AuthenticationProperties properties = new AuthenticationProperties(ISSUER,AUDIENCE,null,null,
                Duration.ofMinutes(15),Duration.ofHours(8),"test:auth","REFRESH","CSRF","/",false);
        return new LoginAuthentication(store,tokens,employees,properties,clock);
    }

    private Employee employee() { return new Employee("test","测试员工",2L,null,null,null,null); }
}
