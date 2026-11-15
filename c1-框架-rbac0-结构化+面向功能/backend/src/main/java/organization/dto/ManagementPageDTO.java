package organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** 管理目录分页，默认第一页 20 项，偏移量限定在 JPA 支持范围内。 */
public record ManagementPageDTO(@Min(1) @Max(21474836) Integer page, @Min(1) @Max(100) Integer pageSize) {
    /** 使用稳定的主键顺序。 */
    public Pageable toPageable() {
        return PageRequest.of(page == null ? 0 : page - 1, pageSize == null ? 20 : pageSize, Sort.by("id"));
    }
}
