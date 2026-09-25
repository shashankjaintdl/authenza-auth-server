package com.authenza.iam.config;

import com.authenza.iam.model.GlobalWebAuthnCredential;
import com.authenza.iam.repository.GlobalWebAuthnCredentialRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jdbc.core.convert.DataAccessStrategy;
import org.springframework.data.jdbc.core.convert.DataAccessStrategyFactory;
import org.springframework.data.jdbc.core.convert.InsertStrategyFactory;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.SqlGeneratorSource;
import org.springframework.data.jdbc.core.convert.SqlParametersFactory;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.jdbc.repository.support.JdbcRepositoryFactory;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * Spring Data JDBC configuration for the IAM module.
 *
 * <p>This config registers <strong>two</strong> JDBC repository scopes:
 *
 * <ol>
 *   <li><b>Tenant-scoped repositories</b>: backed by the multi-tenant {@code RoutingDataSource}
 *       via {@code tenantJdbcOperations}. This covers {@code WebAuthnCredentialRepository},
 *       {@code UserRepository}, etc.</li>
 *
 *   <li><b>Master-scoped {@link GlobalWebAuthnCredentialRepository}</b>: backed by the fixed
 *       {@code masterDataSource} via an explicit {@link JdbcRepositoryFactory} bean, so all
 *       queries and write operations target {@code auth-master.global_webauthn_credentials}
 *       regardless of the active tenant context.</li>
 * </ol>
 */
@Configuration
@EnableJdbcRepositories(
        basePackages = "com.authenza.iam.repository",
        jdbcOperationsRef = "tenantJdbcOperations",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = GlobalWebAuthnCredentialRepository.class
        )
)
public class IamRepositoryConfig {

    /**
     * Manually registers {@link GlobalWebAuthnCredentialRepository} against the master
     * {@code DataSource} using {@link JdbcRepositoryFactory}.
     *
     * <p>A dedicated {@link DataAccessStrategy} is constructed using {@code masterJdbcOps}
     * so that aggregate operations (insert, update, delete, find) target the master
     * database rather than the tenant-routing DataSource.
     */
    @Bean
    public GlobalWebAuthnCredentialRepository globalWebAuthnCredentialRepository(
            @Qualifier("masterDataSource") DataSource masterDataSource,
            RelationalMappingContext mappingContext,
            JdbcConverter converter,
            Dialect dialect,
            ApplicationEventPublisher eventPublisher) {

        NamedParameterJdbcOperations masterJdbcOps =
                new NamedParameterJdbcTemplate(masterDataSource);

        SqlGeneratorSource sqlGeneratorSource =
                new SqlGeneratorSource(mappingContext, converter, dialect);
        SqlParametersFactory sqlParametersFactory =
                new SqlParametersFactory(mappingContext, converter);
        InsertStrategyFactory insertStrategyFactory =
                new InsertStrategyFactory(masterJdbcOps, dialect);
        DataAccessStrategy masterDataAccessStrategy = new DataAccessStrategyFactory(
                sqlGeneratorSource, converter, masterJdbcOps, sqlParametersFactory, insertStrategyFactory
        ).create();

        JdbcRepositoryFactory factory = new JdbcRepositoryFactory(
                masterDataAccessStrategy,
                mappingContext,
                converter,
                dialect,
                eventPublisher,
                masterJdbcOps);

        return factory.getRepository(GlobalWebAuthnCredentialRepository.class);
    }
}
