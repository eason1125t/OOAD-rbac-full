// 文件职责：读取公司部门目录和组织层级。
// 协作关系：为工作区、管理查询及分配规则提供 JPA 数据。
package organization;

import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** 对 organization 提供 Spring Data JPA 数据访问。 */
public interface OrganizationRepository extends JpaRepository<Organization, Long>, JpaSpecificationExecutor<Organization> {
    /** 为当前页批量判定可见直接子级，避免逐节点查询或泄露隐藏分支。 */
    @Query("""
            select distinct child.parentId from Organization child
            where child.parentId in :parentIds and (:global = true or child.id in :visibleIds)
            """)
    Set<Long> findParentsWithVisibleChildren(Set<Long> parentIds, boolean global, Set<Long> visibleIds);

    /** 按组织类型分页读取全局目录。 */
    Page<Organization> findByOrganizationType(String organizationType, Pageable pageable);

    /** 将目录及总数限定在管理范围内。 */
    Page<Organization> findByOrganizationTypeAndIdIn(String organizationType, Set<Long> ids, Pageable pageable);

    /** 按稳定的组织编码查找导入或演示记录。 */
    java.util.Optional<Organization> findByCode(String code);

    /** 查找公司下属组织，供公司管理员导航使用。 */
    java.util.List<Organization> findByParentId(Long parentId);
}
