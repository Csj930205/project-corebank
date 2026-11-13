package com.corebank.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 로그인 세션. 로그인 한 번에 한 줄이고, 그 뒤 리프레시로 받는 액세스 토큰은 모두 이 세션 번호(sid)를 가진다.
 * 세션을 끊으면(revoke) 그 세션의 액세스 토큰과 리프레시 토큰이 모두 그 즉시 통하지 않는다.
 *
 * <p><b>Persistable</b>: id 를 시퀀스가 아니라 코드에서 정한다(UUID). Spring Data 의 save() 는 "id 가 있으면 이미 있는 행"
 * 으로 보고 persist 대신 merge 를 부르는데, merge 는 먼저 SELECT 로 그 행을 찾는다(쓸데없는 쿼리 한 번). Persistable 의
 * isNew() 로 "새 객체" 라고 직접 알려 주면 바로 INSERT 한다. 저장하거나 DB 에서 읽은 뒤에는 새 객체가 아니다.
 *
 * <p>누구의 세션인지·만료 시각은 바꾸지 않는다(updatable = false, 앱 계정 권한도 없음). 세션을 늘리는 방법은 없고,
 * 다시 로그인해야 한다.
 */
@Getter
@Entity
@Table(name = "auth_session")
public class AuthSession implements Persistable<String> {

	public enum PrincipalType {
		ADMIN, CUSTOMER
	}

	/** 세션을 끊은 이유. DB 의 ck_auth_session_revoked 와 값이 일치해야 한다 */
	public enum RevokeReason {
		/** 사용자가 로그아웃 */
		LOGOUT,
		/** 이미 쓴 리프레시 토큰이 다시 왔다. 누군가 토큰을 훔쳐 쓴 것으로 본다 */
		REUSE_DETECTED,
		/** 계정이 더는 로그인할 수 없는 상태(관리자 승인 취소 등)라 리프레시를 거부했다 */
		ACCOUNT_INACTIVE
	}

	@Id
	@Column(length = 36)
	private String id;

	@Enumerated(EnumType.STRING)
	@Column(name = "principal_type", nullable = false, updatable = false, length = 10)
	private PrincipalType principalType;

	@Column(nullable = false, updatable = false, length = 50)
	private String username;

	@Column(name = "customer_id", updatable = false)
	private Long customerId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private LocalDateTime expiresAt;

	@Column(name = "last_refreshed_at")
	private LocalDateTime lastRefreshedAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "revoke_reason", length = 20)
	private RevokeReason revokeReason;

	@Transient
	private boolean isNew = true;

	protected AuthSession() {
	}

	static AuthSession admin(String username, LocalDateTime now, LocalDateTime expiresAt) {
		return new AuthSession(PrincipalType.ADMIN, username, null, now, expiresAt);
	}

	static AuthSession customer(Long customerId, String username, LocalDateTime now, LocalDateTime expiresAt) {
		return new AuthSession(PrincipalType.CUSTOMER, username, customerId, now, expiresAt);
	}

	private AuthSession(PrincipalType type, String username, Long customerId, LocalDateTime now,
			LocalDateTime expiresAt) {
		this.id = UUID.randomUUID().toString();
		this.principalType = type;
		this.username = username;
		this.customerId = customerId;
		this.createdAt = now;
		this.expiresAt = expiresAt;
	}

	/** 끊기지 않았고 절대 만료 전인지 */
	public boolean isActiveAt(LocalDateTime now) {
		return revokedAt == null && now.isBefore(expiresAt);
	}

	void refreshed(LocalDateTime now) {
		this.lastRefreshedAt = now;
	}

	/** 끊는다. 이미 끊긴 세션은 처음 이유를 그대로 둔다(로그아웃을 두 번 눌러도 기록이 바뀌지 않는다). */
	void revoke(RevokeReason reason, LocalDateTime now) {
		if (revokedAt != null) {
			return;
		}
		this.revokedAt = now;
		this.revokeReason = reason;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	// 저장(INSERT)했거나 DB 에서 읽은 객체는 새 객체가 아니다. 이 표시가 없으면 같은 객체를 다시 save 할 때 또 INSERT 한다.
	@PostPersist
	@PostLoad
	void markNotNew() {
		this.isNew = false;
	}

}
