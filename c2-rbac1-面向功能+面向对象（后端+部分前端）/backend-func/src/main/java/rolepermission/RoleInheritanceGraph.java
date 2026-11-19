// 为调用方保存当前继承关系，图计算交给面向功能版的规则函数。
package rolepermission;

import java.util.Map;
import java.util.Set;

public record RoleInheritanceGraph(Map<Long, Set<Long>> parents) {
    public Set<Long> inheritedRoles(Set<Long> roots) {
        return RoleInheritanceRules.reachable(parents, roots);
    }

    public Set<Long> dependingRoles(Long roleId) {
        return RoleInheritanceRules.reachable(RoleInheritanceRules.reverse(parents), Set.of(roleId));
    }

    boolean wouldFormCycle(Long seniorRoleId, Long juniorRoleId) {
        return RoleInheritanceRules.wouldFormCycle(parents, seniorRoleId, juniorRoleId);
    }
}
