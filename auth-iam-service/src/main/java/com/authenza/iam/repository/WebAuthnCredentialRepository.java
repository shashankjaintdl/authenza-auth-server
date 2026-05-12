package com.authenza.iam.repository;

import com.authenza.iam.model.WebAuthnCredential;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for FIDO2/WebAuthn credentials stored in the tenant's database.
 * Each tenant's credential data is isolated in its own DB via the RoutingDataSource.
 */
@Repository
public interface WebAuthnCredentialRepository extends CrudRepository<WebAuthnCredential, Long> {

    /** Finds all passkeys registered by a specific user (for "Manage Passkeys" UI). */
    @Query("SELECT * FROM webauthn_credential WHERE user_id = :userId")
    List<WebAuthnCredential> findAllByUserId(Long userId);

    /** Finds a single credential by its FIDO2 Credential ID (used during authentication). */
    @Query("SELECT * FROM webauthn_credential WHERE credential_id = :credentialId")
    Optional<WebAuthnCredential> findByCredentialId(String credentialId);

    /** Checks if a user has at least one registered passkey (used for login page UX). */
    @Query("SELECT COUNT(*) > 0 FROM webauthn_credential WHERE user_id = :userId")
    boolean existsByUserId(Long userId);

    /** Deletes all credentials for a user (e.g., when their account is deleted). */
    @Modifying
    @Query("DELETE FROM webauthn_credential WHERE user_id = :userId")
    void deleteAllByUserId(Long userId);
}
