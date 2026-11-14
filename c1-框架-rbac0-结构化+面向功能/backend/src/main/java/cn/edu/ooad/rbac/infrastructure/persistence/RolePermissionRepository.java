package cn.edu.ooad.rbac.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 role_permission 提供 Spring Data JPA 数据访问。 */
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {}
