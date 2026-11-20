// 文件职责：对显式邻接表执行无副作用的继承图计算。
// 协作关系：被 RoleInheritanceGraph 委托以展开角色、查环和计算逆向影响。
package rolepermission;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 对显式邻接表执行无副作用的继承图计算，被 RoleInheritanceGraph 委托以展开角色、查环和计算逆向影响。 */
final class RoleInheritanceRules {
    /** 禁止实例化仅提供静态规则或嵌套模型的工具容器。 */
    private RoleInheritanceRules() {
    }

    /** 从低级角色查找高级角色；已有返回路径时新增边会闭环。 */
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

    /** 将高级到低级的边反向，用于查找被权限变更影响的高级角色。 */
    static Map<Long, Set<Long>> reverse(Map<Long, Set<Long>> parents) {
        Map<Long, Set<Long>> reversed = new HashMap<>();
        parents.forEach((senior, juniors) -> juniors.forEach(junior ->
                reversed.computeIfAbsent(junior, unused -> new HashSet<>()).add(senior)));
        return reversed;
    }
}
