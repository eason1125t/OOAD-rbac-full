package cn.edu.ooad.rbac.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** organization 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "organization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    private String organizationType;
    private Long parentId;
    private String managerName;
    private String sourceParentName;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public Organization(String code, String name, String organizationType, Long parentId, String managerName, String sourceParentName) {
        this.code = code;
        this.name = name;
        this.organizationType = organizationType;
        this.parentId = parentId;
        this.managerName = managerName;
        this.sourceParentName = sourceParentName;
    }
}
