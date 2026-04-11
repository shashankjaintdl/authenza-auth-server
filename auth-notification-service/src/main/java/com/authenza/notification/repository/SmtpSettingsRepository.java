package com.authenza.notification.repository;

import com.authenza.notification.domain.SmtpSettings;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SmtpSettingsRepository extends CrudRepository<SmtpSettings, Long> {
    Optional<SmtpSettings> findFirstByOrderByIdDesc(); // usually 1 per tenant
}
