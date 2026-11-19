// 纯继承关系计算，供面向功能版的继承维护及实时授权使用。
package rolepermission;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class RoleInheritanceRules {
    private RoleInheritanceRules() {
    }

    static boolean wouldFormCycle(Map<Long, Set<Long>> parents, long seniorRoleId, long juniorRoleId) {
        return reachable(parents, Set.of(juniorRoleId)).contains(seniorRoleId);
    }

    /** 遍历包含入口角色，访问集合使多路径只计一次。 */
    static Set<Long> reachable(Map<Long, Set<Long>> edges, Set<Long> roots) {
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

    static Map<Long, Set<Long>> reverse(Map<Long, Set<Long>> parents) {
        Map<Long, Set<Long>> reversed = new HashMap<>();
        parents.forEach((senior, juniors) -> juniors.forEach(junior ->
                reversed.computeIfAbsent(junior, unused -> new HashSet<>()).add(senior)));
        return reversed;
    }
}
