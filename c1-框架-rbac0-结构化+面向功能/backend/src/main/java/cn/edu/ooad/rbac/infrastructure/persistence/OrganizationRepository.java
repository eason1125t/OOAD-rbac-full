package cn.edu.ooad.rbac.infrastructure.persistence;

import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 对 organization 提供 Spring Data JPA 数据访问。 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    /** 按组织类型分页读取全局目录。 */
    Page<Organization> findByOrganizationType(String organizationType, Pageable pageable);

    /** 将目录及总数限定在管理范围内。 */
    Page<Organization> findByOrganizationTypeAndIdIn(String organizationType, Set<Long> ids, Pageable pageable);

    /** 按稳定的组织编码查找导入或演示记录。 */
    java.util.Optional<Organization> findByCode(String code);

    /** 查找公司下属组织，供公司管理员导航使用。 */
    java.util.List<Organization> findByParentId(Long parentId);
}
