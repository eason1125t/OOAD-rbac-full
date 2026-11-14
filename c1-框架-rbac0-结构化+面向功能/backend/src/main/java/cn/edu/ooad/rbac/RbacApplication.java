package cn.edu.ooad.rbac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/** RBAC0 启动入口，加载 Flyway、JPA 和显式初始化命令。 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
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
