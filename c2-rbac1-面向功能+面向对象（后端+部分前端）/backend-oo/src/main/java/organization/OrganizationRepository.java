// 文件职责：读取公司部门目录和组织层级。
// 协作关系：为工作区、管理查询及分配规则提供 JPA 数据。
package organization;

import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 对 organization 提供 Spring Data JPA 数据访问。 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    /** 原表目录包含建模为公司的原表行，同时保留演示部门。 */
    @Query("select o from Organization o where o.organizationType = 'DEPARTMENT' or o.code like 'xlsx-%'")
    Page<Organization> findDepartmentDirectory(Pageable pageable);

    /** 公司和部门管理员只读取其可管理范围内的目录行。 */
    @Query("""
            select o from Organization o where o.id in :ids
              and (o.organizationType = 'DEPARTMENT' or o.code like 'xlsx-%')
            """)
    Page<Organization> findDepartmentDirectoryIn(Set<Long> ids, Pageable pageable);
    /** 按组织类型分页读取全局目录。 */
    Page<Organization> findByOrganizationType(String organizationType, Pageable pageable);

    /** 将目录及总数限定在管理范围内。 */
    Page<Organization> findByOrganizationTypeAndIdIn(String organizationType, Set<Long> ids, Pageable pageable);

    /** 按稳定的组织编码查找导入或演示记录。 */
    java.util.Optional<Organization> findByCode(String code);

    /** 查找公司下属组织，供公司管理员导航使用。 */
    java.util.List<Organization> findByParentId(Long parentId);
}
