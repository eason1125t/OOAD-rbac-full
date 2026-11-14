package cn.edu.ooad.rbac.api.vo;

/** 所有认证及授权失败使用的稳定 JSON 错误结构。 */
public record ApiErrorVO(String code, String message) {
}
