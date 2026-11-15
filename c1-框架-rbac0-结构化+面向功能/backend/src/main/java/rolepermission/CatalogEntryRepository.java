package rolepermission;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 catalog_entry 提供 Spring Data JPA 数据访问。 */
public interface CatalogEntryRepository extends JpaRepository<CatalogEntry, Long> {
}
