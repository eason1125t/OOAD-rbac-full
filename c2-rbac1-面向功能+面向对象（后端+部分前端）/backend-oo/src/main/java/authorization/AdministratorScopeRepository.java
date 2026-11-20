// 文件职责：按员工查询管理员配置。
// 协作关系：供工作区计算、管理范围验证及员工维护引用检查。
package authorization;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 administrator_scope 提供 Spring Data JPA 数据访问。 */
public interface AdministratorScopeRepository extends JpaRepository<AdministratorScope, Long> {
}
