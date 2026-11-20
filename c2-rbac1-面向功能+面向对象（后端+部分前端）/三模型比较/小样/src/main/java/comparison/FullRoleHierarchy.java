// 文件职责：由领域对象主导继承用例的装载、校验和保存协作。
// 协作关系：依赖存储和策略两个窄端口；调用方负责可信身份及外层事务。
package comparison;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 每次用例创建的领域协调对象；不是第三套正式后端或共享单例。 */
public final class FullRoleHierarchy {
    public static final String ADDED = "ADDED";
    public static final String UNCHANGED = "UNCHANGED";
    private final InheritanceStore store;
    private final GrantPolicy policy;
    private Map<Long, Set<Long>> parents = Map.of();

    /** 依赖业务端口，不接触 JPA、Spring Security 或具体数据库。 */
    public FullRoleHierarchy(InheritanceStore store, GrantPolicy policy) {
        this.store = store;
        this.policy = policy;
    }

    /** 锁后装载，拒绝先于保存，保存成功后才更新本次对象的关系。 */
    public String addInheritance(Long actor, Long senior, Long junior) {
        store.lockWrites();
        InheritanceStore.Role receiving = store.role(senior);
        InheritanceStore.Role providing = senior.equals(junior) ? receiving : store.role(junior);
        policy.requireManaged(actor, receiving);
        policy.requireManaged(actor, providing);
        if (!Objects.equals(receiving.owner(), providing.owner())) {
            throw new IllegalArgumentException("SCOPE_MISMATCH");
        }
        if (senior.equals(junior)) {
            throw new IllegalArgumentException("SELF_INHERITANCE");
        }
        Map<Long, Set<Long>> before = store.load();
        if (before.getOrDefault(senior, Set.of()).contains(junior)) {
            parents = before;
            return UNCHANGED;
        }
        if (reachable(before, Set.of(junior)).contains(senior)) {
            throw new IllegalArgumentException("CYCLE");
        }
        policy.requireGrant(actor, receiving, providing, immutable(before));
        store.save(senior, junior);
        before.computeIfAbsent(senior, unused -> new HashSet<>()).add(junior);
        parents = before;
        return ADDED;
    }

    /** 只读查询装载本次关系，闭包含根且按集合去重。 */
    public Set<Long> inheritedRoles(Set<Long> roots) {
        parents = store.load();
        return reachable(parents, roots);
    }

    /** 反向查找全部依赖方，供小样对照影响范围。 */
    public Set<Long> dependingRoles(Long role) {
        parents = store.load();
        Map<Long, Set<Long>> reversed = new HashMap<>();
        parents.forEach((senior, juniors) -> juniors.forEach(junior ->
                reversed.computeIfAbsent(junior, unused -> new HashSet<>()).add(senior)));
        return reachable(reversed, Set.of(role));
    }

    /** 用队列和访问集合保证多路径只展开一次，复杂度 O(V+E)。 */
    private static Set<Long> reachable(Map<Long, Set<Long>> graph, Set<Long> roots) {
        ArrayDeque<Long> pending = new ArrayDeque<>(roots);
        Set<Long> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Long current = pending.removeFirst();
            if (visited.add(current)) {
                pending.addAll(graph.getOrDefault(current, Set.of()));
            }
        }
        return Set.copyOf(visited);
    }

    /** 策略仅获得不可变快照，不能偷偷修改待保存的关系。 */
    private static Map<Long, Set<Long>> immutable(Map<Long, Set<Long>> source) {
        Map<Long, Set<Long>> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }
}
