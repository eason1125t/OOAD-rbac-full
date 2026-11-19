// 显式初始化两版的合成账号，沿用现有密码编码器。
package authentication;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import organization.Employee;
import organization.EmployeeRepository;

/** 显式启用时为已初始化的合成员工创建账号。 */
@Component
@Order(20)
@ConditionalOnProperty(name = "rbac.demo.accounts-enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoLoginAccounts implements ApplicationRunner {
    private static final String DEMO_PASSWORD = "1234";
    private static final List<String> USERNAMES = List.of(
            "super-admin", "company-a-admin", "engineering-admin", "engineering-employee", "finance-employee",
            "engineering-requester", "engineering-inherited-approver", "company-b-employee");

    private final EmployeeRepository employees;
    private final LoginAccountRepository accounts;
    private final PasswordEncoder passwords;

    /** 显式运行演示初始化时，创建或重置合成账号。 */
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (!arguments.containsOption("init-demo")) {
            return;
        }
        for (String username : USERNAMES) {
            Employee employee = employees.findByUsername(username).orElseThrow();
            LoginAccount account = accounts.findById(employee.getId()).orElse(null);
            if (account == null || !passwords.matches(DEMO_PASSWORD, account.getPasswordHash())) {
                accounts.save(new LoginAccount(employee.getId(), passwords.encode(DEMO_PASSWORD)));
            }
        }
    }
}
