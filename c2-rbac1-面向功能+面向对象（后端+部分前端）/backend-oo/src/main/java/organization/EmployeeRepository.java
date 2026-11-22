// 文件职责：分页查询、定位和锁定员工资料。
// 协作关系：供管理维护、工作区验证、初始化和角色分配使用。
package organization;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

/** 对 employee 提供 Spring Data JPA 数据访问。 */
public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {
    /** 串行化员工维护与角色分配，避免调岗期间写入旧范围的角色。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Employee> findLockedById(Long id);

    /** 按员工用户名查询。 */
    java.util.Optional<Employee> findByUsername(String username);
}
