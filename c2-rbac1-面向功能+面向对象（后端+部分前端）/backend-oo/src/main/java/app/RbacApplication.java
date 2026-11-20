// 文件职责：启动 C2 RBAC1 独立后端并选择 Web 或初始化模式。
// 协作关系：扫描各业务包，由 InitializationCommand 执行显式初始化。
package app;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** C2 RBAC1 启动入口，加载同级功能包中的组件、实体和仓储。 */
@SpringBootApplication(
        scanBasePackages = {"app", "initialization", "authentication", "organization", "rolepermission",
                "assignment", "authorization", "simulation", "contract"},
        exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan(basePackages = {
        "authentication", "organization", "rolepermission", "assignment", "authorization", "simulation"
})
@EnableJpaRepositories(basePackages = {
        "authentication", "organization", "rolepermission", "assignment", "authorization", "simulation"
})
public class RbacApplication {
    /** 启动数据库迁移，并处理可选的初始化参数。 */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RbacApplication.class);
        DefaultApplicationArguments arguments = new DefaultApplicationArguments(args);
        if (arguments.containsOption("init-demo") || arguments.containsOption("import-excel")) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }
}
