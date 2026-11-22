// 文件职责：校验 JWT、独立登录期限及 Cookie/Redis 配置，不保存用户版本号。
// 协作关系：由 AuthenticationConfiguration 绑定；供签发器、登录服务、Redis 仓储和 HTTP Cookie 使用。
package authentication;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/** 私钥只绑定外部资源位置；仅启动 Web 认证时读取，初始化命令无需私钥。 */
@ConfigurationProperties("rbac.auth")
public record AuthenticationProperties(String issuer, String audience, Resource privateKeyLocation,
        Resource publicKeyLocation, Duration accessTtl, Duration loginTtl, String namespace,
        String refreshCookieName, String csrfCookieName, String cookiePath, boolean cookieSecure) {
    public AuthenticationProperties {
        Assert.hasText(issuer, "JWT issuer is required");
        Assert.hasText(audience, "JWT audience is required");
        Assert.isTrue(accessTtl != null && accessTtl.getSeconds() > 0 && accessTtl.getNano() == 0
                && accessTtl.compareTo(Duration.ofMinutes(15)) <= 0, "Access TTL must be within 15 minutes");
        Assert.isTrue(loginTtl != null && loginTtl.getSeconds() > 0 && loginTtl.getNano() == 0
                && loginTtl.compareTo(Duration.ofHours(8)) <= 0, "Login TTL must be within eight hours");
        Assert.isTrue(namespace != null && namespace.matches("[A-Za-z0-9:_-]+"), "Invalid auth namespace");
        Assert.isTrue(refreshCookieName != null && refreshCookieName.matches("[A-Za-z0-9_-]{1,64}"),
                "Invalid refresh cookie name");
        Assert.isTrue(csrfCookieName != null && csrfCookieName.matches("[A-Za-z0-9_-]{1,64}")
                && !csrfCookieName.equals(refreshCookieName), "Invalid CSRF cookie name");
        Assert.isTrue(cookiePath != null && cookiePath.matches("/[A-Za-z0-9/_-]*")
                && cookiePath.endsWith("/"), "Invalid public auth cookie path");
    }
}
