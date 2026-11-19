// 持有当前角色层级的领域对象，负责判断成环与展开双向影响范围。
package rolepermission.domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class RoleHierarchy {
    private final Map<Long, Set<Long>> parents;

    public RoleHierarchy(Map<Long, Set<Long>> parents) {
        this.parents = parents.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    /** 当低级角色可达高级角色时，新增边会闭环。 */
    public boolean wouldFormCycle(long seniorRoleId, long juniorRoleId) {
        return inheritedRoles(Set.of(juniorRoleId)).contains(seniorRoleId);
    }

    public Set<Long> inheritedRoles(Set<Long> roots) {
        return reachable(parents, roots);
    }

    public Set<Long> dependingRoles(Long roleId) {
        return reachable(reverse(), Set.of(roleId));
    }

    private Map<Long, Set<Long>> reverse() {
        Map<Long, Set<Long>> reversed = new HashMap<>();
        parents.forEach((senior, juniors) -> juniors.forEach(junior ->
                reversed.computeIfAbsent(junior, unused -> new HashSet<>()).add(senior)));
        return reversed;
    }

    private static Set<Long> reachable(Map<Long, Set<Long>> edges, Set<Long> roots) {
        Deque<Long> pending = new ArrayDeque<>(roots);
        Set<Long> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Long roleId = pending.removeFirst();
            if (visited.add(roleId)) {
                pending.addAll(edges.getOrDefault(roleId, Set.of()));
            }
        }
        return visited;
    }
}
