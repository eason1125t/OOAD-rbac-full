// 文件职责：独立核验账号密码及最新员工登录资格，不建立任何 Session 或 JWT。
// 协作关系：由 AuthController 调用；复用现有账号仓储、密码编码器和 CurrentEmployeeService 资格规则。
package authentication;

import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import organization.Employee;
import organization.EmployeeRepository;

/** 凭据核验与登录凭证生命周期分离，未知账号仍执行一次密码计算。 */
@Service
public class AccountAuthentication {
    private final EmployeeRepository employees;
    private final LoginAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final String fallbackHash;

    public AccountAuthentication(EmployeeRepository employees, LoginAccountRepository accounts,
                                 PasswordEncoder passwords) {
        this.employees = employees;
        this.accounts = accounts;
        this.passwords = passwords;
        this.fallbackHash = passwords.encode(UUID.randomUUID().toString());
    }

    /** 统一拒绝未知账号、错误密码及不可登录员工，不暴露账号状态差异。 */
    public Employee verify(String username, String password) {
        Employee employee = employees.findByUsername(username).orElse(null);
        LoginAccount account = employee == null ? null : accounts.findById(employee.getId()).orElse(null);
        boolean matched = passwords.matches(password, account == null ? fallbackHash : account.getPasswordHash());
        if (!matched || account == null || !CurrentEmployeeService.canSignIn(employee)) {
            throw new BadCredentialsException("用户名或密码无效，或账号不可登录");
        }
        return employee;
    }
}
