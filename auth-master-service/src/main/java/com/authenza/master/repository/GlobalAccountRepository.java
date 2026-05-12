package com.authenza.master.repository;

import com.authenza.master.model.GlobalAccount;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GlobalAccountRepository extends ListCrudRepository<GlobalAccount, Long> {

    /** Used during login fallback to find the BCrypt hash for a shadow admin. */
    Optional<GlobalAccount> findByEmail(String email);

    /** Used during registration to prevent duplicate accounts. */
    boolean existsByEmail(String email);
}
