package authorization;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 administrator_scope 提供 Spring Data JPA 数据访问。 */
public interface AdministratorScopeRepository extends JpaRepository<AdministratorScope, Long> {
}
