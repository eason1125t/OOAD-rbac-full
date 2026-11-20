// 文件职责：保存功能版关系快照并转交纯函数计算。
// 协作关系：供授权与分配调用，算法集中在 RoleInheritanceRules。
package rolepermission;

import java.util.Map;
import java.util.Set;

/** 保存功能版关系快照并转交纯函数计算，供授权与分配调用，算法集中在 RoleInheritanceRules。 */
public record RoleInheritanceGraph(Map<Long, Set<Long>> parents) {
    /** 包含入口角色向权限来源展开，访问集合消除多路径重复。 */
    public Set<Long> inheritedRoles(Set<Long> roots) {
        return RoleInheritanceRules.reachable(parents, roots);
    }

    /** 反向展开所有依赖该角色的高级角色，供新增授权影响检查使用。 */
    public Set<Long> dependingRoles(Long roleId) {
        return RoleInheritanceRules.reachable(RoleInheritanceRules.reverse(parents), Set.of(roleId));
    }

    /** 从低级角色查找高级角色；已有返回路径时新增边会闭环。 */
    boolean wouldFormCycle(Long seniorRoleId, Long juniorRoleId) {
        return RoleInheritanceRules.wouldFormCycle(parents, seniorRoleId, juniorRoleId);
    }
}
