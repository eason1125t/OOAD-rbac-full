// 文件职责：组合申请分页、记录总数与当前可用写动作。
// 协作关系：由申请 Controller 输出，动作列表不替代服务端再次鉴权。
package simulation.vo;

import java.util.List;

/** 申请列表的 1 基分页响应。 */
public record RequestPageVO(List<RequestSummaryVO> items, int page, int pageSize, long total,
                            List<String> allowedActions) {
}
