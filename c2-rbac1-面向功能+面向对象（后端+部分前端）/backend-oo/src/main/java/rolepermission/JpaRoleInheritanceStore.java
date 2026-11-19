// 将继承表转换为角色关系快照，并归口 JPA 写入与锁。
package rolepermission;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import rolepermission.domain.RoleHierarchy;

@Repository
@RequiredArgsConstructor
class JpaRoleInheritanceStore implements RoleInheritanceStore {
    private final RoleInheritanceRepository repository;

    public void lockWrites() {
        if (repository.lockGraph() == null) {
            throw new org.springframework.dao.DataRetrievalFailureException("角色层级锁记录不存在");
        }
    }

    public RoleHierarchy snapshot() {
        Map<Long, Set<Long>> parents = new HashMap<>();
        for (RoleInheritance edge : repository.findAll()) {
            parents.computeIfAbsent(edge.getSeniorRoleId(), unused -> new HashSet<>()).add(edge.getJuniorRoleId());
        }
        return new RoleHierarchy(parents);
    }

    public boolean contains(RoleInheritanceKey key) {
        return repository.existsById(key);
    }

    public void add(RoleInheritanceKey key) {
        repository.save(new RoleInheritance(key));
    }

    public void remove(RoleInheritanceKey key) {
        repository.deleteById(key);
    }

    public boolean references(Long roleId) {
        return repository.existsBySeniorRoleIdOrJuniorRoleId(roleId, roleId);
    }
}
