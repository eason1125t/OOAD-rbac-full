// 申请用例所需的业务系统标识，由系统查询适配器提供。
package simulation.domain;

/** 应用层可见的系统上下文。 */
public record RequestSystem(Long id, String code) {
}
