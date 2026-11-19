// 角色继承的仓储端口，供应用服务和授权读取当前领域关系。
package rolepermission;

import rolepermission.domain.RoleHierarchy;

public interface RoleInheritanceStore {
    void lockWrites();

    RoleHierarchy snapshot();

    boolean contains(RoleInheritanceKey key);

    void add(RoleInheritanceKey key);

    void remove(RoleInheritanceKey key);

    boolean references(Long roleId);
}
