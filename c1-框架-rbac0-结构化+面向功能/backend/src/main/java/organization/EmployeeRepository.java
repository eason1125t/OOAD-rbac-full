package organization;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** 对 employee 提供 Spring Data JPA 数据访问。 */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    /** 串行化员工维护与角色分配，避免调岗期间写入旧范围的角色。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Employee> findLockedById(Long id);

    /** 分页结果和总数只包含管理范围内的员工。 */
    Page<Employee> findByOrganizationIdIn(Set<Long> organizationIds, Pageable pageable);

    /** 按员工用户名查询。 */
    java.util.Optional<Employee> findByUsername(String username);
}
