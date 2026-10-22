package com.grandvista.auth.repository;

import com.grandvista.auth.domain.Role;
import com.grandvista.auth.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID>, JpaSpecificationExecutor<UserAccount> {

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("select count(u) > 0 from UserAccount u join u.roles r where r = :role")
    boolean existsByRole(Role role);

    @Query("select count(u) from UserAccount u join u.roles r where r = :role and u.enabled = true")
    long countEnabledByRole(Role role);

}
