package cn.edu.ooad.rbac.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** business_system 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "business_system")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessSystem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    private Long ownerOrganizationId;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public BusinessSystem(String code, String name, Long ownerOrganizationId) {
        this.code = code;
        this.name = name;
        this.ownerOrganizationId = ownerOrganizationId;
    }
}
