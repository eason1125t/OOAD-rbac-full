// 文件职责：表达申请用例所需的系统标识。
// 协作关系：由系统端口适配器提供，避免应用服务依赖 JPA BusinessSystem。
package simulation.domain;

/** 应用层的系统标识，保留类型边界，不携带未消费的目录字段。 */
public record RequestSystem(Long id) {
}
