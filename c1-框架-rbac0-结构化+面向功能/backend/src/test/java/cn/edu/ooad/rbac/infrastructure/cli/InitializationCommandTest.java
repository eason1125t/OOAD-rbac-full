package cn.edu.ooad.rbac.infrastructure.cli;

import cn.edu.ooad.rbac.service.InitializationService;
import cn.edu.ooad.rbac.service.InitializationService.ImportResult;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证初始化命令如何校验参数并委托给 Service。 */
class InitializationCommandTest {
    private final InitializationService initialization = mock(InitializationService.class);
    private final InitializationCommand command = new InitializationCommand(initialization);

    @Test
    @DisplayName("C1-M1-03 指定目录时执行三表导入")
    void importsGivenDirectory() throws Exception {
        Path directory = Path.of("sample-excel");
        when(initialization.importWorkbooks(directory)).thenReturn(new ImportResult(2, 1, 3, 1));

        command.run(new DefaultApplicationArguments("--import-excel=sample-excel"));

        verify(initialization).importWorkbooks(directory);
    }

    @Test
    @DisplayName("C1-M1-05 演示初始化须显式启用")
    void initializesDemoOnlyWhenRequested() throws Exception {
        command.run(new DefaultApplicationArguments());
        verifyNoInteractions(initialization);

        command.run(new DefaultApplicationArguments("--init-demo"));
        verify(initialization).initializeDemo();
    }

    @Test
    @DisplayName("C1-M1-04 拒绝冲突命令和非法路径参数")
    void rejectsInvalidArgumentsBeforeWriting() {
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--init-demo", "--import-excel=sample")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("分别执行");
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--import-excel")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("请指定");
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--import-excel=")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("请指定");
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--import-excel=a", "--import-excel=b")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("请指定");

        verifyNoInteractions(initialization);
    }
}
