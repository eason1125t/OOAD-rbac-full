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
}
