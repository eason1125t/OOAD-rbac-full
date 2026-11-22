// 文件职责：表达登录记录和刷新凭证的确定性拒绝，不把存储故障当成401。
// 协作关系：由 RedisLoginStore/LoginAuthentication 抛出，安全链或 Controller 转换为统一错误。
package authentication;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** 错误文本不包含凭证、摘要、员工 ID 或内部存储细节。 */
public final class AuthenticationFailure extends OAuth2AuthenticationException {
    public enum Reason {
        AUTHENTICATION_REQUIRED("请登录后重试"),
        REFRESH_TOKEN_INVALID("刷新凭证无效，请重新登录"),
        REFRESH_TOKEN_REUSED("登录凭证已失效，请重新登录");

        private final String message;
        Reason(String message) { this.message = message; }
    }

    public AuthenticationFailure(Reason reason) {
        super(new OAuth2Error(reason.name()), reason.message);
    }
}
