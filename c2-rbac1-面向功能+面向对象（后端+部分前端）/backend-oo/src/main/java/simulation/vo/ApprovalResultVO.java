// 文件职责：保留原审批接口的授权与执行结果响应。
// 协作关系：由 BusinessRequestController 在审批事务成功返回后构造。
package simulation.vo;

/** 分别向业务端报告鉴权结果和实际执行结果。 */
public record ApprovalResultVO(String authorization, String execution, Long requestId, String status) {
}
