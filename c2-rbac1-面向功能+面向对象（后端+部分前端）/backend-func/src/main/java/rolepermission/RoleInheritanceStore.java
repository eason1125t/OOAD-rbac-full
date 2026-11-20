// 文件职责：封装继承图的快照、写锁与关系持久化。
// 协作关系：供继承管理、分配检查、管理查询及实时授权共同使用。
package rolepermission;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 封装继承图的快照、写锁与关系持久化，供继承管理、分配检查、管理查询及实时授权共同使用。 */
@Repository
@RequiredArgsConstructor
public class RoleInheritanceStore {
    private final RoleInheritanceRepository repository;

    /** 锁定唯一层级锁记录，写入方先持锁再读取关系快照。 */
    public void lockWrites() {
        if (repository.lockGraph() == null) {
            throw new org.springframework.dao.DataRetrievalFailureException("角色层级锁记录不存在");
        }
    }

    /** 从直接继承边构造邻接表，供本次业务检查和权限展开使用。 */
    public RoleInheritanceGraph snapshot() {
        Map<Long, Set<Long>> parents = new HashMap<>();
        for (RoleInheritance edge : repository.findAll()) {
            parents.computeIfAbsent(edge.getSeniorRoleId(), unused -> new HashSet<>()).add(edge.getJuniorRoleId());
        }
        return new RoleInheritanceGraph(parents);
    }

    /** 检查直接继承边是否已存在，使合法重复添加保持幂等。 */
    public boolean contains(RoleInheritanceKey key) {
        return repository.existsById(key);
    }

    /** 保存直接继承边；调用方须已持图写锁并完成业务校验。 */
    public void add(RoleInheritanceKey key) {
        repository.save(new RoleInheritance(key));
    }

    /** 删除直接继承边；同一事务内的锁和范围检查由调用方负责。 */
    public void remove(RoleInheritanceKey key) {
        repository.deleteById(key);
    }

    /** 检查角色任一方向的继承引用，删除角色前必须全部解除。 */
    public boolean references(Long roleId) {
        return repository.existsBySeniorRoleIdOrJuniorRoleId(roleId, roleId);
    }
}
