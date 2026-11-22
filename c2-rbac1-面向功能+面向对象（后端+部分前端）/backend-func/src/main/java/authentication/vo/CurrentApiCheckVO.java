// 文件职责：公开当前员工对指定系统 API 的系统级权限判断。
// 协作关系：由 CurrentAuthorizationService 构造，AuthController 输出；不作为后续授权凭证。
package authentication.vo;

public record CurrentApiCheckVO(String systemCode, String operationId, boolean allowed) {
}
