package com.kacp.nanwang.dm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 描述：DM8 只读数据源（NANWANG_RO，compatibleMode=mysql）
 */
@Configuration
public class DmDataSourceConfig {

    @Bean
    public DataSource dmDataSource(
            @Value("${nanwang.dm.url:jdbc:dm://127.0.0.1:5236?compatibleMode=mysql}") String url,
            @Value("${nanwang.dm.username:NANWANG_RO}") String username,
            @Value("${nanwang.dm.password}") String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setDriverClassName("dm.jdbc.driver.DmDriver");
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("nanwang-dm8-ro");
        cfg.setReadOnly(true);
        cfg.setConnectionTimeout(10_000);
        cfg.setMaxLifetime(1_800_000);
        return new HikariDataSource(cfg);
    }
}
