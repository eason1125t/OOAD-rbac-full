// 文件职责：把已验证 JWT 转换为现有 Long 员工 principal，并强制检查登录记录和最新员工资格。
// 协作关系：交给 Spring JwtAuthenticationProvider；LoginVerifier由RedisLoginStore提供。
package authentication;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.Assert;

/** 由AuthenticationConfiguration装配；登录记录与员工资格都是认证的必要条件。 */
public final class JwtEmployeeAuthentication implements Converter<Jwt, AbstractAuthenticationToken> {
    private final CurrentEmployeeService employees;
    private final LoginVerifier logins;

    public JwtEmployeeAuthentication(CurrentEmployeeService employees, LoginVerifier logins) {
        Assert.notNull(employees, "CurrentEmployeeService is required");
        Assert.notNull(logins, "LoginVerifier is required");
        this.employees = employees;
        this.logins = logins;
    }

    /** 只能由已完成验签和契约校验的 Provider 调用；从不映射 JWT roles/scope 到业务权限。 */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long employeeId = JwtAccessTokens.employeeId(jwt.getSubject());
        String sid = jwt.getClaimAsString("sid");
        if (employeeId == null || !JwtAccessTokens.isRandomId(sid)) {
            throw new OAuth2AuthenticationException("invalid_token");
        }
        // 存储故障原样传播，不能降级为签名正确即可通过；失败也不继续查询员工。
        logins.requireActive(sid, employeeId);
        if (employees.findActive(employeeId).isEmpty()) {
            logins.rejectEmployee(sid, employeeId);
            throw new OAuth2AuthenticationException("invalid_token");
        }
        return UsernamePasswordAuthenticationToken.authenticated(employeeId, null, List.of());
    }

    /** 核对随机 sid 的存在、归属和绝对期限；不是用户版本号或角色缓存。 */
    public interface LoginVerifier {
        void requireActive(String sid, Long employeeId);
        /** 当前请求已验证身份但员工失去资格，结束此sid；不撤销同账号其他独立登录。 */
        void rejectEmployee(String sid, Long employeeId);
    }
}
