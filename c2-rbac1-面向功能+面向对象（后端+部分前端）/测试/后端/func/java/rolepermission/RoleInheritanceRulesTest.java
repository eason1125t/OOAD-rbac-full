// 文件职责：验证 RoleInheritanceRulesTest 对应的行为、拒绝及不变量。
// 协作关系：由所属 Maven 工程执行，测试源统一位于 C2/测试。
// 验证角色继承规则，对应 C2-03 自继承拒绝。
package rolepermission;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoleInheritanceRulesTest {
    @Test
    @DisplayName("C2-03 角色不能继承自身")
    void rejectsSelfInheritance() {
        assertThat(RoleInheritanceRules.wouldFormCycle(Map.of(), 17L, 17L)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {2L, 3L})
    @DisplayName("C2-03 添加反向边或闭合传递链时拒绝")
    void rejectsClosingAnExistingPath(long seniorRoleId) {
        Map<Long, Set<Long>> parents = Map.of(1L, Set.of(2L), 2L, Set.of(3L));
        assertThat(RoleInheritanceRules.wouldFormCycle(parents, seniorRoleId, 1L)).isTrue();
    }

    @Test
    @DisplayName("C2-03 保留未成环的正向连接")
    void acceptsAnAcyclicEdge() {
        Map<Long, Set<Long>> parents = Map.of(1L, Set.of(2L), 2L, Set.of(3L));
        assertThat(RoleInheritanceRules.wouldFormCycle(parents, 1L, 3L)).isFalse();
    }

    /** 菱形继承有两条路径，但每个可达角色只出现一次；不接入孤立角色。 */
    @Test
    @DisplayName("C2-01 / C2-02 多路径闭包去重且不跨孤立分支")
    void expandsDiamondWithoutDuplicates() {
        Map<Long, Set<Long>> graph = Map.of(1L, Set.of(2L, 3L), 2L, Set.of(4L),
                3L, Set.of(4L), 9L, Set.of(10L));
        assertThat(RoleInheritanceRules.reachable(graph, Set.of(1L))).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    /** 低级角色变更必须找到所有依赖方，包含自身并排除不相关分支。 */
    @Test
    @DisplayName("C2-07 逆向影响覆盖全部上游角色")
    void findsAllDependingRoles() {
        Map<Long, Set<Long>> graph = Map.of(1L, Set.of(2L, 3L), 2L, Set.of(4L),
                3L, Set.of(4L), 9L, Set.of(10L));
        assertThat(RoleInheritanceRules.reachable(RoleInheritanceRules.reverse(graph), Set.of(4L))).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }
}
