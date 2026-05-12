package com.authenza.iam.repository;

import com.authenza.common.model.iam.Role;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends CrudRepository<Role, Long> {

    @Query("SELECT * FROM role WHERE name = :name")
    Optional<Role> findByName(String name);
}
