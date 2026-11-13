package com.corebank.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 리프레시 토큰 한 개. 원문은 저장하지 않고 해시만 둔다(RefreshTokens.hash).
 *
 * <p>쓰면(리프레시) used_at 을 채우고 새 토큰을 하나 더 만든다(회전). 그래서 한 세션에 토큰 줄이 여러 개 쌓이고,
 * 그중 쓰지 않은 것은 마지막 하나뿐이다. 쓴 토큰을 남겨 두는 이유는 "다시 쓰는 시도" 를 알아보기 위해서다.
 * 지워 버리면 도난당한 옛 토큰이 그냥 "없는 토큰" 으로 보여 세션을 끊을 근거가 사라진다.
 */
@Getter
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "refresh_token_seq_generator")
	@SequenceGenerator(name = "refresh_token_seq_generator", sequenceName = "refresh_token_seq", allocationSize = 1)
	private Long id;

	// 리프레시할 때 늘 세션을 함께 본다. 저장소 쿼리가 JOIN FETCH 로 같이 읽는다(LAZY 라도 쿼리는 한 번).
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false, updatable = false)
	private AuthSession session;

	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private LocalDateTime expiresAt;

	@Column(name = "used_at")
	private LocalDateTime usedAt;

	protected RefreshToken() {
	}

	static RefreshToken of(AuthSession session, String tokenHash, LocalDateTime now, LocalDateTime expiresAt) {
		RefreshToken token = new RefreshToken();
		token.session = session;
		token.tokenHash = tokenHash;
		token.createdAt = now;
		token.expiresAt = expiresAt;
		return token;
	}

	boolean isUsed() {
		return usedAt != null;
	}

	void markUsed(LocalDateTime now) {
		this.usedAt = now;
	}

}
