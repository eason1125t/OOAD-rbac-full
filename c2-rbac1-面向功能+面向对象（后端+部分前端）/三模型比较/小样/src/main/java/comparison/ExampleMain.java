// 文件职责：直接运行满血内存小样，展示合法新增及重复幂等。
// 协作关系：在外层准备可信身份和策略，再调用 FullRoleHierarchy；没有数据库事务。
package comparison;

import java.util.Map;
import java.util.Set;

/** 演示入口；纯内存小样不提供 Web 服务。 */
public final class ExampleMain {
    private ExampleMain() {
    }

    /** 构造同归属夹具，领域对象负责后续协作，控制台只输出合成数据。 */
    public static void main(String[] args) {
        InMemoryInheritanceStore store = new InMemoryInheritanceStore(Map.of(
                1L, new InheritanceStore.Role(1L, 7L),
                2L, new InheritanceStore.Role(2L, 7L)), Map.of());
        GrantPolicy policy = new GrantPolicy() {
            /** 仅用于演示的显式管理员，不对应真实认证系统。 */
            public void requireManaged(Long actor, InheritanceStore.Role role) {
                if (!actor.equals(100L)) {
                    throw new SecurityException("DENIED");
                }
            }
            /** 本演示不配置停用，复杂策略由测试夹具另行演示。 */
            public void requireGrant(Long actor, InheritanceStore.Role senior,
                                     InheritanceStore.Role junior, Map<Long, Set<Long>> before) {
            }
        };
        FullRoleHierarchy hierarchy = new FullRoleHierarchy(store, policy);
        System.out.println(hierarchy.addInheritance(100L, 1L, 2L));
        System.out.println(hierarchy.addInheritance(100L, 1L, 2L));
        System.out.println(hierarchy.inheritedRoles(Set.of(1L)).size());
    }
}
