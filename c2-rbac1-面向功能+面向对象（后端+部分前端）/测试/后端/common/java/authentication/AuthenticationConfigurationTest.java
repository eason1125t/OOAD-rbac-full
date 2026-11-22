// 文件职责：验证Web启动缺少外部密钥必须失败，而CLI初始化不需要JWT私钥。
// 协作关系：使用真实AuthenticationConfiguration和属性绑定，不启动数据库或读取部署密钥。
package authentication;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthenticationConfigurationTest {
    private static final String[] SETTINGS = {
        "rbac.auth.issuer=urn:test", "rbac.auth.audience=test", "rbac.auth.access-ttl=15m",
        "rbac.auth.login-ttl=8h", "rbac.auth.namespace=test:auth", "rbac.auth.refresh-cookie-name=REFRESH",
        "rbac.auth.csrf-cookie-name=CSRF", "rbac.auth.cookie-path=/", "rbac.auth.cookie-secure=false",
        "rbac.auth.private-key-location=file:/nonexistent-c2-test-private.pem",
        "rbac.auth.public-key-location=file:/nonexistent-c2-test-public.pem"
    };

    @Test
    void webStartupFailsInsteadOfGeneratingDefaultKey() {
        new WebApplicationContextRunner().withUserConfiguration(AuthenticationConfiguration.class)
                .withBean(CurrentEmployeeService.class, () -> mock(CurrentEmployeeService.class))
                .withBean(RedisLoginStore.class, () -> mock(RedisLoginStore.class))
                .withPropertyValues(SETTINGS).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("JWT 密钥配置无效");
                });
    }

    @Test
    void nonWebInitializationDoesNotLoadPrivateKey() {
        new ApplicationContextRunner().withUserConfiguration(AuthenticationConfiguration.class)
                .withPropertyValues(SETTINGS).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Clock.class).doesNotHaveBean(JwtAccessTokens.class);
                });
    }
}
