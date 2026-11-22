// 文件职责：按同一可见范围查询部门目录/树及员工搜索分页。
// 协作关系：复用DirectoryVisibilityService，使用JPA Specification同时约束列表和count，返回映射所需结果。
package organization;

import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import organization.DirectoryVisibilityService.Scope;
import organization.dto.DepartmentDirectoryQueryDTO;
import organization.dto.EmployeeDirectoryQueryDTO;

/** 数据范围始终在数据库筛选/count中执行，不以分页后的内存过滤实现隔离。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectoryQueryService {
    private final DirectoryVisibilityService visibility;
    private final OrganizationRepository organizations;
    private final EmployeeRepository employees;

    public EmployeePage employees(Long actor, EmployeeDirectoryQueryDTO filter, Pageable page) {
        Scope scope = visibility.forCurrentEmployee(actor);
        if (filter.departmentId() != null) {
            Organization department = organization(filter.departmentId());
            if (!"DEPARTMENT".equals(department.getOrganizationType())) {
                throw new BusinessAccessException(Reason.VALIDATION_FAILED);
            }
            requireVisible(department, scope);
        }
        String keyword = keyword(filter.q());
        Specification<Employee> specification = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(visible(cb, root.get("organizationId"), scope));
            if (!scope.administrator()) {
                conditions.add(cb.isTrue(root.get("enabled")));
            }
            if (filter.departmentId() != null) {
                conditions.add(cb.equal(root.get("organizationId"), filter.departmentId()));
            }
            conditions.add(matches(cb, root.get("name"), root.get("username"), keyword));
            return cb.and(conditions.toArray(Predicate[]::new));
        };
        return new EmployeePage(employees.findAll(specification, page), scope.administrator());
    }

    public DepartmentPage departments(Long actor, DepartmentDirectoryQueryDTO filter, Pageable page) {
        filter.validateMode();
        Scope scope = filter.tree() ? visibility.forCurrentEmployee(actor) : visibility.forAdministrator(actor);
        if (filter.parentId() != null) {
            requireVisible(organization(filter.parentId()), scope);
        }
        String keyword = keyword(filter.q());
        boolean roots = filter.rootsOnly() != null ? filter.rootsOnly()
                : filter.parentId() == null && keyword == null;
        Specification<Organization> specification = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(visible(cb, root.get("id"), scope));
            if (!filter.tree()) {
                conditions.add(cb.or(cb.equal(root.get("organizationType"), "DEPARTMENT"),
                        cb.like(root.get("code"), "xlsx-%")));
            } else if (filter.parentId() != null) {
                conditions.add(cb.equal(root.get("parentId"), filter.parentId()));
            } else if (roots) {
                conditions.add(scope.global() ? cb.isNull(root.get("parentId"))
                        : cb.or(cb.isNull(root.get("parentId")), root.get("parentId").in(scope.ids()).not()));
            }
            conditions.add(matches(cb, root.get("name"), root.get("code"), keyword));
            return cb.and(conditions.toArray(Predicate[]::new));
        };
        Page<Organization> rows = organizations.findAll(specification, page);
        Set<Long> parents = rows.stream().map(Organization::getId).collect(Collectors.toSet());
        // 只对本页批量查一次可见子级；子级是否匹配q不影响hasChildren。
        Set<Long> expandable = filter.tree() && !parents.isEmpty()
                ? organizations.findParentsWithVisibleChildren(parents, scope.global(), scope.ids()) : Set.of();
        return new DepartmentPage(rows, expandable);
    }

    private Organization organization(Long id) {
        return organizations.findById(id).orElseThrow(() -> new BusinessAccessException(Reason.RESOURCE_NOT_FOUND));
    }

    private void requireVisible(Organization organization, Scope scope) {
        if (!scope.contains(organization.getId())) {
            throw new BusinessAccessException(Reason.PERMISSION_DENIED, "无权查看该组织的数据");
        }
    }

    private static Predicate visible(CriteriaBuilder cb, Expression<Long> id, Scope scope) {
        return scope.global() ? cb.conjunction() : id.in(scope.ids());
    }

    /** 使用显式!转义，避免MySQL默认反斜线语义把用户输入当作LIKE通配符。 */
    private static Predicate matches(CriteriaBuilder cb, Expression<String> first, Expression<String> second,
                                     String keyword) {
        if (keyword == null) {
            return cb.conjunction();
        }
        String escaped = keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        String pattern = "%" + escaped + "%";
        return cb.or(cb.like(first, pattern, '!'), cb.like(second, pattern, '!'));
    }

    private static String keyword(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record EmployeePage(Page<Employee> rows, boolean administrator) {
    }

    public record DepartmentPage(Page<Organization> rows, Set<Long> expandableIds) {
    }
}
