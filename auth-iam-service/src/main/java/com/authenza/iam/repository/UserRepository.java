package com.authenza.iam.repository;

import com.authenza.common.model.iam.User;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.springframework.data.domain.Page;

@Repository
public interface UserRepository extends CrudRepository<User, Long>, PagingAndSortingRepository<User, Long> {

    @Query("SELECT * FROM application_user WHERE email = :email")
    Optional<User> findByEmail(String email);

    @Query("SELECT * FROM application_user WHERE preferred_username = :username")
    Optional<User> findByPreferredUsername(String username);

    Page<User> findByPasswordNot(String password, org.springframework.data.domain.Pageable pageable);
}
