package com.corebank.admin;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

	Optional<AdminUser> findByUsername(String username);

	boolean existsByUsername(String username);

	List<AdminUser> findByStatus(AdminUserStatus status, Sort sort);

}
