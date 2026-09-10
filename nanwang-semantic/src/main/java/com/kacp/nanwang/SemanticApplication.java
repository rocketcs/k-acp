package com.kacp.nanwang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;

/**
 * 描述：南网问数语义服务启动类
 * <p>
 * 独立于平台构建的 MCP 服务：DM8 直连 + JSqlParser 校验器 + Neo4j 证据子图，
 * 以 MCP Streamable HTTP（/mcp）对外提供问数治理工具面。
 */
@SpringBootApplication
public class SemanticApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticApplication.class, args);
    }

    @Bean
    public ServletRegistrationBean<?> mcpServletRegistration(
            org.springframework.context.ApplicationContext context) {
        HttpServletStreamableServerTransportProvider provider =
                context.getBean(HttpServletStreamableServerTransportProvider.class);
        ServletRegistrationBean<?> reg = new ServletRegistrationBean<>(provider, "/mcp/*");
        reg.setName("nanwangMcpServlet");
        reg.setLoadOnStartup(1);
        return reg;
    }
}
