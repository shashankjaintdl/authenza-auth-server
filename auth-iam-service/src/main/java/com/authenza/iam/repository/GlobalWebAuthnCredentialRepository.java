package com.authenza.iam.repository;

import com.authenza.iam.model.GlobalWebAuthnCredential;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JDBC repository for FIDO2 passkeys that belong to
 * <strong>globally-registered users</strong> ({@code global_accounts}).
 *
 * <p>This repository is intentionally wired to the <strong>master {@code DataSource}</strong>
 * (not the tenant-routing DS) via {@code IamRepositoryConfig#masterJdbcConfiguration}.
 * Every query operates on {@code auth-master.global_webauthn_credentials}.
 *
 * <p>Routing decision:
 * <ul>
 *   <li>If the authenticating user is found in {@code global_accounts} → use this repo.</li>
 *   <li>If the user is a tenant-local employee → use {@link WebAuthnCredentialRepository}.</li>
 * </ul>
 */
@Repository
public interface GlobalWebAuthnCredentialRepository extends CrudRepository<GlobalWebAuthnCredential, Long> {

    /** Returns all passkeys registered by a global account (for "Manage Passkeys" UI). */
    @Query("SELECT * FROM global_webauthn_credentials WHERE account_id = :accountId")
    List<GlobalWebAuthnCredential> findAllByAccountId(Long accountId);

    /** Looks up a passkey by its FIDO2 Credential ID (used during assertion verification). */
    @Query("SELECT * FROM global_webauthn_credentials WHERE credential_id = :credentialId")
    Optional<GlobalWebAuthnCredential> findByCredentialId(String credentialId);

    /** Returns {@code true} if the global account has at least one registered passkey. */
    @Query("SELECT COUNT(*) > 0 FROM global_webauthn_credentials WHERE account_id = :accountId")
    boolean existsByAccountId(Long accountId);

    /** Removes all passkeys for a global account (e.g., account deletion or security reset). */
    @Modifying
    @Query("DELETE FROM global_webauthn_credentials WHERE account_id = :accountId")
    void deleteAllByAccountId(Long accountId);
}
