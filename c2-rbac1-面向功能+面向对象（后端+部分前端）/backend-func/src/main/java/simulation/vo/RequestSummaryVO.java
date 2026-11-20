// 文件职责：返回申请列表的精简公开字段。
// 协作关系：由申请 Controller 映射 Service 列表元素。
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

    /** 将应用摘要映射为列表响应，仅包含 ID、标题和状态。 */
    public static RequestSummaryVO from(RequestSummary result) {
        return CloneFactory.copy(new RequestSummaryVO(), result);
    }
}
