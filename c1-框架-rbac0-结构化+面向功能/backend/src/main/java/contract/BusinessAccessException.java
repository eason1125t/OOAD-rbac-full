package contract;

import lombok.Getter;

/** 用例和授权守卫使用的失败原因，由 API 转换为 HTTP 响应。 */
@Getter
public class BusinessAccessException extends RuntimeException {
    /** 对应首批 OpenAPI 的稳定错误码。 */
    public enum Reason {
        AUTHENTICATION_REQUIRED("请登录后重试"),
        SYSTEM_ACCESS_DENIED("无权进入该业务系统"),
        PERMISSION_DENIED("缺少审批权限或操作已停用"),
        SYSTEM_NOT_FOUND("业务系统不存在"),
        REQUEST_NOT_FOUND("申请不存在"),
        REQUEST_ALREADY_APPROVED("申请已审批"),
        RESOURCE_NOT_FOUND("员工、角色或权限不存在"),
        ASSIGNMENT_CONFLICT("当前状态不允许建立分配"),
        RESOURCE_CONFLICT("资源重复或仍被引用");

        private final String message;

        /** 为失败原因关联可直接展示的中文消息。 */
        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    /** 以明确的业务失败原因中断用例。 */
    public BusinessAccessException(Reason reason) {
        this(reason, reason.message);
    }

    /** 相同错误码可保留对应场景的说明。 */
    public BusinessAccessException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
