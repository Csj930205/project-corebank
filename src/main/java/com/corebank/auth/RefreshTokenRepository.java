package com.corebank.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	/**
	 * 해시로 토큰을 찾고 잠근다. 세션도 함께 읽는다(JOIN FETCH). Oracle 의 FOR UPDATE 는 OF 를 적지 않으면 JOIN 한 두 테이블의
	 * 행을 모두 잠근다. 그래서 같은 토큰으로 두 리프레시가 동시에 와도, 리프레시와 로그아웃이 겹쳐도 한 줄로 처리된다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM RefreshToken t JOIN FETCH t.session WHERE t.tokenHash = :hash")
	Optional<RefreshToken> findByHashForUpdate(@Param("hash") String hash);

}
