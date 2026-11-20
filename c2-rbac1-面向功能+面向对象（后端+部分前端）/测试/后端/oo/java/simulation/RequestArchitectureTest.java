// 文件职责：验证 RequestArchitectureTest 对应的行为、拒绝及不变量。
// 协作关系：由所属 Maven 工程执行，测试源统一位于 C2/测试。
// 防止申请应用服务重新耦合到 JPA、Spring Data 或持久化实体。
package simulation;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestArchitectureTest {
    @Test
    @DisplayName("C2-16 申请应用服务只通过业务端口依赖外部组件")
    void dependsOnPorts() {
        assertThat(Arrays.stream(BusinessRequestService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(Field::getType))
                .allMatch(type -> type.isInterface() && type.getPackageName().equals("simulation.port"));
        assertThat(Arrays.stream(BusinessRequestService.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .noneMatch(type -> type.getPackageName().startsWith("org.springframework.data")
                        || type.isAnnotationPresent(jakarta.persistence.Entity.class));
    }
}
