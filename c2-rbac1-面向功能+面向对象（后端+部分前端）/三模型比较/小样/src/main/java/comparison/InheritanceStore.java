// 文件职责：定义小样的角色、关系装载与原子写入边界。
// 协作关系：由 FullRoleHierarchy 主动调用，内存实现不冒充数据库锁和事务。
package comparison;

import java.util.Map;
import java.util.Set;

/** 本次用例的存储端口；真实实现须由外层事务覆盖锁与保存。 */
public interface InheritanceStore {
    /** 角色的明确组织归属；空归属表示共享角色。 */
    record Role(Long id, Long owner) {
    }
    /** 写入前取得关系锁；内存单线程实现只保留该协作边界。 */
    void lockWrites();
    /** 查找真实端点；缺失不能创建悬空继承边。 */
    Role role(Long id);
    /** 返回隔离的关系副本，不允许领域对象修改存储内部集合。 */
    Map<Long, Set<Long>> load();
    /** 原子保存单条边；抛异常时不得保存部分结果。 */
    void save(Long senior, Long junior);
}
