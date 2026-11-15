package simulation.vo;

import java.util.List;

/** 申请列表的 1 基分页响应。 */
public record RequestPageVO(List<RequestSummaryVO> items, int page, int pageSize, long total) {
}
