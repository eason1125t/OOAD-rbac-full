// 文件职责：读取继承边并锁定全局层级写锁记录。
// 协作关系：由继承存储封装，为继承、分配及角色删除协调写入。
package rolepermission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 读取继承边并锁定全局层级写锁记录，由继承存储封装，为继承、分配及角色删除协调写入。 */
interface RoleInheritanceRepository extends JpaRepository<RoleInheritance, RoleInheritanceKey> {
    /** 检查角色是否出现在任一继承边两端，保护角色删除引用。 */
    boolean existsBySeniorRoleIdOrJuniorRoleId(Long seniorRoleId, Long juniorRoleId);

    /** 所有依赖继承图做决策的写入先锁同一行，再读取图。 */
    @Query(value = "select id from role_hierarchy_lock where id = 1 for update", nativeQuery = true)
    Integer lockGraph();
}
