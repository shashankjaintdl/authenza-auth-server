package com.authenza.notification.repository;

import com.authenza.notification.domain.BrandingSettings;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrandingSettingsRepository extends CrudRepository<BrandingSettings, Long> {
    Optional<BrandingSettings> findFirstByOrderByIdDesc();
}
