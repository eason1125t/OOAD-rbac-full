// 申请公开响应，由 CloneFactory 从应用结果复制同名同类型字段。
package simulation.vo;

import cn.edu.xmu.clonefactory.CopyFrom;
import cn.edu.xmu.clonefactory.util.CloneFactory;
import lombok.Getter;
import lombok.Setter;
import simulation.BusinessRequestService.RequestSummary;

/** 保持共同 OpenAPI 的申请响应字段。 */
@Getter
@Setter
@CopyFrom(RequestSummary.class)
public class RequestSummaryVO {
    private Long id;
    private String title;
    private String status;

    /** 将应用结果映射为响应，保留历史记录的空申请人。 */
    public static RequestSummaryVO from(RequestSummary result) {
        return CloneFactory.copy(new RequestSummaryVO(), result);
    }
}