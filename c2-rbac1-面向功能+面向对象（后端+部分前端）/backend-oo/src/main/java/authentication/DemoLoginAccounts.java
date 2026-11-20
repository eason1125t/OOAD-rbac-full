// 文件职责：按显式开关初始化演示账号和密码哈希。
// 协作关系：与 InitializationService 的合成员工数据协作，不自动开放普通账号。
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
