// 文件职责：封装不可变角色关系快照及双向可达性规则。
// 协作关系：由继承仓储装载，被继承、分配及授权用例调用。
package rolepermission.domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 封装不可变角色关系快照及双向可达性规则，由继承仓储装载，被继承、分配及授权用例调用。 */
public final class RoleHierarchy {
    private final Map<Long, Set<Long>> parents;

    /** 复制邻接表及各级集合为不可变快照，避免调用方修改已装载关系。 */
    public RoleHierarchy(Map<Long, Set<Long>> parents) {
        this.parents = parents.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    /** 当低级角色可达高级角色时，新增边会闭环。 */
    public boolean wouldFormCycle(long seniorRoleId, long juniorRoleId) {
        return inheritedRoles(Set.of(juniorRoleId)).contains(seniorRoleId);
    }

    /** 包含入口角色向权限来源展开，访问集合消除多路径重复。 */
    public Set<Long> inheritedRoles(Set<Long> roots) {
        return reachable(parents, roots);
    }

    /** 反向展开所有依赖该角色的高级角色，供新增授权影响检查使用。 */
    public Set<Long> dependingRoles(Long roleId) {
        return reachable(reverse(), Set.of(roleId));
    }

    /** 将高级到低级的边反向，用于查找被权限变更影响的高级角色。 */
    private Map<Long, Set<Long>> reverse() {
        Map<Long, Set<Long>> reversed = new HashMap<>();
        parents.forEach((senior, juniors) -> juniors.forEach(junior ->
                reversed.computeIfAbsent(junior, unused -> new HashSet<>()).add(senior)));
        return reversed;
    }

    /** 用队列和访问集合计算可达闭包，每个角色至多展开一次，时间为 O(V+E)。 */
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
