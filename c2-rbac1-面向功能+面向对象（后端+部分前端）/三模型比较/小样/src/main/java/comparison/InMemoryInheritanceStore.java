// 文件职责：实现隔离的内存角色和继承边存储。
// 协作关系：供小样和行为测试使用，不连接生产库，不实现数据库并发锁。
package comparison;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 可观察的内存适配器；不提供跨线程事务保证。 */
public class InMemoryInheritanceStore implements InheritanceStore {
    private final Map<Long, Role> roles;
    private final Map<Long, Set<Long>> edges = new HashMap<>();

    /** 防御复制输入，避免调用方绕过存储边界改变夹具。 */
    public InMemoryInheritanceStore(Map<Long, Role> roles, Map<Long, Set<Long>> initial) {
        this.roles = Map.copyOf(roles);
        initial.forEach((key, value) -> edges.put(key, new HashSet<>(value)));
    }

    /** 单线程小样无数据库锁，此操作明确不证明并发安全。 */
    @Override
    public void lockWrites() {
    }

    /** 缺失端点按小样的明确原因拒绝。 */
    @Override
    public Role role(Long id) {
        Role result = roles.get(id);
        if (result == null) {
            throw new IllegalArgumentException("ROLE_NOT_FOUND");
        }
        return result;
    }

    /** 隔离装载结果，写操作必须显式经过 save。 */
    @Override
    public Map<Long, Set<Long>> load() {
        Map<Long, Set<Long>> result = new HashMap<>();
        edges.forEach((key, value) -> result.put(key, new HashSet<>(value)));
        return result;
    }

    /** 单条集合插入自然幂等，不接受悬空端点。 */
    @Override
    public void save(Long senior, Long junior) {
        role(senior);
        role(junior);
        edges.computeIfAbsent(senior, unused -> new HashSet<>()).add(junior);
    }
}
