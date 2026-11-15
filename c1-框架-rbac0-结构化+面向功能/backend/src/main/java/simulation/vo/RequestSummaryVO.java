package simulation.vo;

import simulation.BusinessRequestService.RequestSummary;

/** 供业务端展示一条申请。 */
public record RequestSummaryVO(Long id, String title, String status) {
    /** 从用例结果组装公开字段。 */
    public static RequestSummaryVO from(RequestSummary item) {
        return new RequestSummaryVO(item.id(), item.title(), item.status());
    }
}
