// 文件职责：分页读取与定位原表功能权限清单。
// 协作关系：供 InitializationService 幂等导入及 ManagementQueryService 展示。
package rolepermission;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 catalog_entry 提供 Spring Data JPA 数据访问。 */
public interface CatalogEntryRepository extends JpaRepository<CatalogEntry, Long> {
}
