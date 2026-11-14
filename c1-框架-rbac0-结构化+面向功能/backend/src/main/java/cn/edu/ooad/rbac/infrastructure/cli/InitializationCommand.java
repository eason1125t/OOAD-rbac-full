package cn.edu.ooad.rbac.infrastructure.cli;

import cn.edu.ooad.rbac.service.InitializationService;
import cn.edu.ooad.rbac.service.InitializationService.ImportResult;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 将显式命令行选项转交初始化 Service，不提供上传接口。 */
@Component
@RequiredArgsConstructor
public class InitializationCommand implements ApplicationRunner {
    private final InitializationService initialization;

    /** 执行选定的数据初始化，并仅输出汇总。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("import-excel") && args.containsOption("init-demo")) {
            throw new IllegalArgumentException("请分别执行 Excel 导入和演示初始化");
        }
        if (args.containsOption("import-excel")) {
            List<String> paths = args.getOptionValues("import-excel");
            if (paths == null || paths.size() != 1 || paths.getFirst().isBlank()) {
                throw new IllegalArgumentException("请指定 --import-excel=<三份工作簿所在目录>");
            }
            ImportResult result = initialization.importWorkbooks(Path.of(paths.getFirst()));
            System.out.printf("导入完成：员工 %d，组织 %d，清单 %d，员工组织待匹配 %d%n",
                    result.employees(), result.organizations(), result.catalogEntries(), result.pending());
        } else if (args.containsOption("init-demo")) {
            initialization.initializeDemo();
            System.out.println("演示数据已就绪");
        }
    }
}
