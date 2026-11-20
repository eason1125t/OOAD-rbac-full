// 文件职责：提供小样的管理范围及新增授权影响校验边界。
// 协作关系：由 FullRoleHierarchy 调用；HTTP认证和外层事务不进入本接口。
package comparison;

import java.util.Map;
import java.util.Set;

/** 本小样只验证显式夹具策略，不代替 C2 的完整组织授权实现。 */
public interface GrantPolicy {
    /** 校验可信操作者能管理端点，共享角色也须显式获准。 */
    void requireManaged(Long actor, InheritanceStore.Role role);
    /** 查环通过后检查新增来源影响，拒绝时不得保存。 */
    void requireGrant(Long actor, InheritanceStore.Role senior, InheritanceStore.Role junior,
                      Map<Long, Set<Long>> before);
}
