package com.authenza.iam.repository;

import com.authenza.iam.model.EmailVerificationToken;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailVerificationTokenRepository  extends CrudRepository<EmailVerificationToken, Long> {

    @Query("SELECT * FROM email_verification_token WHERE token = :token")
    Optional<EmailVerificationToken> findByToken(String token);

    @Modifying
    @Query("DELETE FROM email_verification_token WHERE user_id = :userId")
    void deleteByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM email_verification_token WHERE expires_at < CURRENT_TIMESTAMP")
    void deleteExpiredTokens();
}
