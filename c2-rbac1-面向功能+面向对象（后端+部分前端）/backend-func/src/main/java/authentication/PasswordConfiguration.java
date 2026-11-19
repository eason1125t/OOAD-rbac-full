package authentication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** 命令行演示账号初始化与 HTTP 登录共用同一密码编码器。 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {
    /** 支持契约中的多字节密码长度，并为每个密码使用独立盐。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
