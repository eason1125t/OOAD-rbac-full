package authentication.vo;

import authentication.WorkspaceService.SystemInfo;

/** 供业务前端使用的系统目录条目。 */
public record BusinessSystemVO(Long id, String code, String name, Long ownerOrganizationId, String ownerOrganizationName) {
    /** 将系统查询结果映射为公开响应。 */
    public static BusinessSystemVO from(SystemInfo system) {
        return new BusinessSystemVO(system.id(), system.code(), system.name(), system.ownerOrganizationId(),
                system.ownerOrganizationName());
    }
}
