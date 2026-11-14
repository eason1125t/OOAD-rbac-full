package cn.edu.ooad.rbac.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 rbac_role 提供 Spring Data JPA 数据访问。 */
public interface RbacRoleRepository extends JpaRepository<RbacRole, Long> {
}
