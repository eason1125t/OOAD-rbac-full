// 文件职责：声明统一错误响应的业务码和中文消息。
// 协作关系：由异常处理器及安全过滤器输出，保持与 OpenAPI 一致。
package contract.vo;

/** 所有认证及授权失败使用的稳定 JSON 错误结构。 */
public record ApiErrorVO(String code, String message) {
}
