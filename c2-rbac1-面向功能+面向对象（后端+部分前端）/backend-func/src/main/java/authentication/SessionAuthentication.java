// 文件职责：核验账号并建立、读取和结束可信登录会话。
// 协作关系：衔接 Spring Security、Spring Session Redis 和员工启用状态。
package authentication;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;
import organization.Employee;
import organization.EmployeeRepository;

/** 验证账号并管理 Spring Security 上下文在 Redis 会话中的生命周期。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionAuthentication {
    private final EmployeeRepository employees;
    private final LoginAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository tokens;
    private final String fallbackHash;

    /** 给未知账号准备固定成本的密码校验，统一失败响应。 */
    public SessionAuthentication(EmployeeRepository employees, LoginAccountRepository accounts,
                                 PasswordEncoder passwords, SecurityContextRepository contexts,
                                 CsrfTokenRepository tokens) {
        this.employees = employees;
        this.accounts = accounts;
        this.passwords = passwords;
        this.contexts = contexts;
        this.tokens = tokens;
        this.fallbackHash = passwords.encode(UUID.randomUUID().toString());
    }

    /** 校验账号密码及当前员工状态。 */
    public Employee verify(String username, String password) {
        Employee employee = employees.findByUsername(username).orElse(null);
        LoginAccount account = employee == null ? null : accounts.findById(employee.getId()).orElse(null);
        boolean matched = passwords.matches(password, account == null ? fallbackHash : account.getPasswordHash());
        if (!matched || account == null || !WorkspaceService.canSignIn(employee)) {
            throw new BadCredentialsException("用户名或密码无效，或账号不可登录");
        }
        return employee;
    }

    /** 轮换匿名会话，清除旧令牌，只保存可信员工 ID。 */
    public void start(Long employeeId, HttpServletRequest request, HttpServletResponse response) {
        request.getSession();
        request.changeSessionId();
        tokens.saveToken(null, request, response);

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(employeeId, null, List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contexts.saveContext(context, request, response);
        SecurityContextHolder.setContext(context);
    }

    /** 失效服务端会话并清除当前身份。 */
    public void end(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
    }
}
