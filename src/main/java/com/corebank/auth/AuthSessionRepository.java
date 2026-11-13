package com.corebank.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

	/**
	 * 세션이 살아 있는지(끊기지 않았고 절대 만료 전). 요청마다 부르므로 엔티티를 읽지 않고 기본키로 존재만 본다.
	 */
	@Query("""
			SELECT COUNT(s) > 0 FROM AuthSession s
			WHERE s.id = :id AND s.revokedAt IS NULL AND s.expiresAt > :now
			""")
	boolean isActive(@Param("id") String id, @Param("now") LocalDateTime now);

}
