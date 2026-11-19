package simulation.vo;

/** 分别向业务端报告鉴权结果和实际执行结果。 */
public record ApprovalResultVO(String authorization, String execution, Long requestId, String status) {
}
