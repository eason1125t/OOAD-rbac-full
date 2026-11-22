// 文件职责：绑定认证配置与时钟，并仅为 Web 应用读取外部密钥、装配 JWT 和 Cookie CSRF。
// 协作关系：安全链依赖此处的组件；初始化命令不需要密钥，不生成默认生产密钥。
package authentication;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthenticationProperties.class)
public class AuthenticationConfiguration {
    @Bean
    Clock authenticationClock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    JwtAccessTokens jwtAccessTokens(AuthenticationProperties settings, Clock clock) {
        return JwtAccessTokens.fromPem(settings.issuer(), settings.audience(), settings.privateKeyLocation(),
                settings.publicKeyLocation(), clock, settings.accessTtl());
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    JwtEmployeeAuthentication jwtEmployeeAuthentication(CurrentEmployeeService employees, RedisLoginStore logins) {
        return new JwtEmployeeAuthentication(employees, logins);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    CsrfTokenRepository csrfTokens(AuthenticationProperties settings) {
        CookieCsrfTokenRepository tokens = new CookieCsrfTokenRepository();
        tokens.setCookieName(settings.csrfCookieName());
        tokens.setCookiePath(settings.cookiePath());
        tokens.setHeaderName("X-CSRF-TOKEN");
        tokens.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(settings.cookieSecure()).sameSite("Strict"));
        return tokens;
    }
}
