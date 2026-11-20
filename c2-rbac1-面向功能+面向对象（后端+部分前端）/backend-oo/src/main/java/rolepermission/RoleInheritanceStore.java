// 文件职责：封装继承图的快照、写锁与关系持久化。
// 协作关系：供继承管理、分配检查、管理查询及实时授权共同使用。
package rolepermission;

import rolepermission.domain.RoleHierarchy;

/** 封装继承图的快照、写锁与关系持久化，供继承管理、分配检查、管理查询及实时授权共同使用。 */
public interface RoleInheritanceStore {
    /** 锁定唯一层级锁记录，写入方先持锁再读取关系快照。 */
    void lockWrites();

    /** 从直接继承边构造邻接表，供本次业务检查和权限展开使用。 */
    RoleHierarchy snapshot();

    /** 检查直接继承边是否已存在，使合法重复添加保持幂等。 */
    boolean contains(RoleInheritanceKey key);

    /** 保存直接继承边；调用方须已持图写锁并完成业务校验。 */
    void add(RoleInheritanceKey key);

    /** 删除直接继承边；同一事务内的锁和范围检查由调用方负责。 */
    void remove(RoleInheritanceKey key);

    /** 检查角色任一方向的继承引用，删除角色前必须全部解除。 */
    boolean references(Long roleId);
}
