package authentication;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    private static final List<String> USERNAMES = List.of(
            "super-admin", "company-a-admin", "engineering-admin", "engineering-employee");

    private final EmployeeRepository employees;
    private final LoginAccountRepository accounts;
    private final PasswordEncoder passwords;

    @Value("${RBAC_DEMO_PASSWORD:}")
    private String demoPassword;

    /** 初始化新账号，保留此前已配置的密码。 */
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (!arguments.containsOption("init-demo")) {
            return;
        }
        if (demoPassword.isBlank() || demoPassword.length() > 128) {
            throw new IllegalArgumentException("RBAC_DEMO_PASSWORD 必须是 1 至 128 字符的演示密码");
        }
        for (String username : USERNAMES) {
            Employee employee = employees.findByUsername(username).orElseThrow();
            if (!accounts.existsById(employee.getId())) {
                accounts.save(new LoginAccount(employee.getId(), passwords.encode(demoPassword)));
            }
        }
    }
}
