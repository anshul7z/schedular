package com.data.schedular.config;

import org.springframework.boot.quartz.autoconfigure.QuartzProperties;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.util.Locale;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
public class QuartzConfig {

    static final String DELEGATE = "org.quartz.jobStore.driverDelegateClass";

    /**
     * Picks Quartz's JDBC delegate from the metadata DB actually in use: PostgreSQL stores job data as
     * {@code bytea}, which only {@code PostgreSQLDelegate} reads correctly; H2 works with the standard delegate.
     * An explicitly configured delegate always wins.
     */
    @Bean
    SchedulerFactoryBeanCustomizer quartzDriverDelegate(DataSource dataSource, QuartzProperties quartz) {
        return factory -> {
            Properties properties = new Properties();
            properties.putAll(quartz.getProperties());
            if (!properties.containsKey(DELEGATE)) {
                properties.setProperty(DELEGATE, delegateFor(dataSource));
            }
            factory.setQuartzProperties(properties);
        };
    }

    static String delegateFor(DataSource dataSource) {
        String product;
        try {
            product = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
        } catch (MetaDataAccessException e) {
            throw new IllegalStateException("Cannot detect the metadata database type for Quartz", e);
        }
        return product != null && product.toLowerCase(Locale.ROOT).contains("postgres")
                ? "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate"
                : "org.quartz.impl.jdbcjobstore.StdJDBCDelegate";
    }
}
