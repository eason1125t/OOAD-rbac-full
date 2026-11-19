// 分页申请记录与当前会话在该系统的动作权限。
package simulation.vo;

import java.util.List;

/** 申请列表的 1 基分页响应。 */
public record RequestPageVO(List<RequestSummaryVO> items, int page, int pageSize, long total,
                            List<String> allowedActions) {
}
