// 继承关系仓储，读写继承边并获取数据库中的关系写入锁。
package rolepermission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RoleInheritanceRepository extends JpaRepository<RoleInheritance, RoleInheritanceKey> {
    boolean existsBySeniorRoleIdOrJuniorRoleId(Long seniorRoleId, Long juniorRoleId);

    /** 所有依赖继承图做决策的写入先锁同一行，再读取图。 */
    @Query(value = "select id from role_hierarchy_lock where id = 1 for update", nativeQuery = true)
    Integer lockGraph();
}
