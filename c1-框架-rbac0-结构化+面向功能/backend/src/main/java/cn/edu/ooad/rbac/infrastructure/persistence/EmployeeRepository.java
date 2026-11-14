package cn.edu.ooad.rbac.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 employee 提供 Spring Data JPA 数据访问。 */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    /** 按员工用户名查询。 */
    java.util.Optional<Employee> findByUsername(String username);
}
