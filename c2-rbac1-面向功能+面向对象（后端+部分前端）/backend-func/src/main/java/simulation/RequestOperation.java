// 文件职责：集中定义八个申请 operationId 及固定 ALL 权限要求。
// 协作关系：实际申请用例和 API 映射校验共同使用；不依赖数据库或 HTTP。
package simulation;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 系统级权限要求；申请存在性、所有权和状态仍由业务用例检查。 */
public enum RequestOperation {
    LIST("listSystemRequests", "request:read"),
    DETAIL("getRequest", "request:read"),
    CREATE("createRequest", "request:create"),
    UPDATE("updateRequest", "request:read", "request:update"),
    WITHDRAW("withdrawRequest", "request:read", "request:withdraw"),
    APPROVE("approveRequest", "request:read", "request:approve"),
    REJECT("rejectRequest", "request:read", "request:reject"),
    DELETE("deleteRejectedRequest", "request:read", "request:delete");

    public static final List<String> DEMO_SYSTEMS = List.of(
            "demo-company-app", "demo-engineering-app", "demo-finance-app", "demo-company-b-app");
    private final String operationId;
    private final List<String> requiredPermissions;

    RequestOperation(String operationId, String... requiredPermissions) {
        this.operationId = operationId;
        this.requiredPermissions = List.of(requiredPermissions);
    }

    public String operationId() {
        return operationId;
    }

    public List<String> requiredPermissions() {
        return requiredPermissions;
    }

    public static Optional<RequestOperation> find(String operationId) {
        return Arrays.stream(values()).filter(operation -> operation.operationId.equals(operationId)).findFirst();
    }
}
