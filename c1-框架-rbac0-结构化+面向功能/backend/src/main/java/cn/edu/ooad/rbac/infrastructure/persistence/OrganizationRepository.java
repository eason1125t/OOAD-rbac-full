package cn.edu.ooad.rbac.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 organization 提供 Spring Data JPA 数据访问。 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    /** 按稳定的组织编码查找导入或演示记录。 */
    java.util.Optional<Organization> findByCode(String code);
}
