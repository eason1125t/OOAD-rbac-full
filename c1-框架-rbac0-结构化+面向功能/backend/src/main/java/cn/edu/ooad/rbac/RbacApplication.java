package cn.edu.ooad.rbac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** RBAC0 启动入口，加载 Flyway、JPA 和显式初始化命令。 */
@SpringBootApplication
public class RbacApplication {
    /** 启动数据库迁移，并处理可选的初始化参数。 */
    public static void main(String[] args) {
        SpringApplication.run(RbacApplication.class, args);
    }
}
