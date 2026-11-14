package cn.edu.ooad.rbac.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 对 business_system 提供 Spring Data JPA 数据访问。 */
public interface BusinessSystemRepository extends JpaRepository<BusinessSystem, Long> {
    /** 通过契约公开的稳定编码查找系统。 */
    Optional<BusinessSystem> findByCode(String code);

    /** 按稳定编码列出全局可管理的系统。 */
    java.util.List<BusinessSystem> findAllByOrderByCodeAsc();

    /** 按组织范围筛选系统，避免在客户端处理准入。 */
    java.util.List<BusinessSystem> findByOwnerOrganizationIdInOrderByCodeAsc(java.util.Set<Long> organizationIds);
}
