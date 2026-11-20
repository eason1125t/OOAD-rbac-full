// 文件职责：独立期望验证满血小样的继承、范围及失败不保存。
// 协作关系：直接运行领域对象及内存适配器，不替代真实数据库或HTTP回归。
package comparison;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 输入与预期手写，测试关系状态而非内部方法调用次数。 */
class FullRoleHierarchyTest {
    /** 同归属夹具，角色3属于另一组织；9/10用于孤立分支。 */
    private InMemoryInheritanceStore store(Map<Long, Set<Long>> edges) {
        return new InMemoryInheritanceStore(Map.of(1L, new InheritanceStore.Role(1L, 7L),
                2L, new InheritanceStore.Role(2L, 7L), 3L, new InheritanceStore.Role(3L, 8L),
                4L, new InheritanceStore.Role(4L, 7L), 9L, new InheritanceStore.Role(9L, 7L),
                10L, new InheritanceStore.Role(10L, 7L)), edges);
    }

    /** 仅模拟可信操作者与停用策略，未复制完整生产授权规则。 */
    private GrantPolicy policy(boolean disabled) {
        return new GrantPolicy() {
            @Override
            public void requireManaged(Long actor, InheritanceStore.Role role) {
                if (!actor.equals(100L)) {
                    throw new SecurityException("DENIED");
                }
            }
            @Override
            public void requireGrant(Long actor, InheritanceStore.Role senior, InheritanceStore.Role junior,
                                     Map<Long, Set<Long>> before) {
                if (disabled) {
                    throw new SecurityException("DISABLED");
                }
            }
        };
    }

    /** 首次新增保存一条边，重复调用不增加关系。 */
    @Test
    @DisplayName("C2-01 / C2-05 合法新增及幂等")
    void addsAndKeepsIdempotent() {
        InMemoryInheritanceStore storage = store(Map.of());
        FullRoleHierarchy model = new FullRoleHierarchy(storage, policy(false));
        assertEquals("ADDED", assertDoesNotThrow(() -> model.addInheritance(100L, 1L, 2L)));
        assertEquals("UNCHANGED", model.addInheritance(100L, 1L, 2L));
        assertEquals(Map.of(1L, Set.of(2L)), storage.load());
    }

    /** 自继承拒绝，原关系完整。 */
    @Test
    @DisplayName("C2-03 自继承不写入")
    void rejectsSelf() {
        InMemoryInheritanceStore storage = store(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new FullRoleHierarchy(storage, policy(false)).addInheritance(100L, 1L, 1L));
        assertEquals(Map.of(), storage.load());
    }

    /** 低级角色已可达高级角色时，新增边会闭环。 */
    @Test
    @DisplayName("C2-03 直接及传递环路不写入")
    void rejectsCycles() {
        for (Map<Long, Set<Long>> graph : List.of(Map.of(1L, Set.of(2L)),
                Map.of(1L, Set.of(4L), 4L, Set.of(2L)))) {
            InMemoryInheritanceStore storage = store(graph);
            assertThrows(IllegalArgumentException.class,
                    () -> new FullRoleHierarchy(storage, policy(false)).addInheritance(100L, 2L, 1L));
            assertEquals(graph, storage.load());
        }
    }

    /** 已有传递路径不妨碍同方向合法直达边。 */
    @Test
    @DisplayName("C2-03 同向直达边合法")
    void acceptsShortcut() {
        InMemoryInheritanceStore storage = store(Map.of(1L, Set.of(4L), 4L, Set.of(2L)));
        FullRoleHierarchy model = new FullRoleHierarchy(storage, policy(false));
        assertEquals("ADDED", model.addInheritance(100L, 1L, 2L));
        assertEquals(Set.of(1L, 4L, 2L), model.inheritedRoles(Set.of(1L)));
    }

    /** 菱形闭包只包含相关分支，正反方向均去重。 */
    @Test
    @DisplayName("C2-02 / C2-07 菱形去重与逆向影响")
    void expandsDiamond() {
        FullRoleHierarchy model = new FullRoleHierarchy(store(Map.of(1L, Set.of(2L, 4L),
                2L, Set.of(10L), 4L, Set.of(10L), 9L, Set.of())), policy(false));
        assertEquals(Set.of(1L, 2L, 4L, 10L), model.inheritedRoles(Set.of(1L)));
        assertEquals(Set.of(1L, 2L, 4L, 10L), model.dependingRoles(10L));
        assertEquals(Set.of(), model.inheritedRoles(Set.of()));
    }

    /** 跨归属和越权均不能产生边。 */
    @Test
    @DisplayName("C2-05 归属与管理拒绝无写入")
    void rejectsInvalidScope() {
        InMemoryInheritanceStore storage = store(Map.of());
        FullRoleHierarchy model = new FullRoleHierarchy(storage, policy(false));
        assertThrows(IllegalArgumentException.class, () -> model.addInheritance(100L, 1L, 3L));
        assertThrows(SecurityException.class, () -> model.addInheritance(99L, 1L, 2L));
        assertEquals(Map.of(), storage.load());
    }

    /** 策略拒绝和缺失端点都不能绕过保存入口。 */
    @Test
    @DisplayName("C2-05 / C2-07 缺失角色与停用拒绝")
    void rejectsMissingAndDisabled() {
        InMemoryInheritanceStore storage = store(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new FullRoleHierarchy(storage, policy(false)).addInheritance(100L, 1L, 999L));
        assertThrows(SecurityException.class,
                () -> new FullRoleHierarchy(storage, policy(true)).addInheritance(100L, 1L, 2L));
        assertEquals(Map.of(), storage.load());
    }

    /** 装载副本隔离，调用方不能绕过领域操作修改实际关系。 */
    @Test
    @DisplayName("C2-05 存储副本隔离")
    void isolatesLoadedData() {
        InMemoryInheritanceStore storage = store(Map.of(1L, Set.of(2L)));
        storage.load().get(1L).clear();
        assertEquals(Map.of(1L, Set.of(2L)), storage.load());
    }

    /** 存储抛出异常时，已装载的关系不能先被领域对象修改。 */
    @Test
    @DisplayName("C2-05 保存失败不产生继承边")
    void preservesGraphOnSaveFailure() {
        InMemoryInheritanceStore failing = new InMemoryInheritanceStore(Map.of(
                1L, new InheritanceStore.Role(1L, 7L), 2L, new InheritanceStore.Role(2L, 7L)), Map.of()) {
            @Override
            public void save(Long senior, Long junior) {
                throw new IllegalStateException("SAVE_FAILED");
            }
        };
        FullRoleHierarchy model = new FullRoleHierarchy(failing, policy(false));
        assertThrows(IllegalStateException.class, () -> model.addInheritance(100L, 1L, 2L));
        assertEquals(Map.of(), failing.load());
        assertEquals(Set.of(1L), model.inheritedRoles(Set.of(1L)));
    }

    /** 策略拿到不可变快照，不能修改关系再宣称校验通过。 */
    @Test
    @DisplayName("C2-07 策略快照不可篡改")
    void rejectsPolicyMutation() {
        InMemoryInheritanceStore storage = store(Map.of(1L, Set.of(4L)));
        GrantPolicy mutating = new GrantPolicy() {
            @Override
            public void requireManaged(Long actor, InheritanceStore.Role role) {
            }
            @Override
            public void requireGrant(Long actor, InheritanceStore.Role senior,
                                     InheritanceStore.Role junior, Map<Long, Set<Long>> before) {
                before.get(1L).clear();
            }
        };
        assertThrows(UnsupportedOperationException.class,
                () -> new FullRoleHierarchy(storage, mutating).addInheritance(100L, 1L, 2L));
        assertEquals(Map.of(1L, Set.of(4L)), storage.load());
    }
}
