package com.corebank.admin;

import com.corebank.common.DbTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 관리자 계정. 비밀번호 원문은 어디에도 남기지 않고 해시만 저장한다.
 *
 * <p>역할(role)은 하나뿐이고 바꾸지 않는다. 운영자가 등록을 요청하면 PENDING 이고, 책임자가 승인해야 ACTIVE 가 되어
 * 로그인할 수 있다(AuthService). 서버 설정(.env)으로 만든 계정만 요청 없이 처음부터 ACTIVE 다.
 */
@Getter
@Entity
@Table(name = "admin_user")
public class AdminUser {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "admin_user_seq_generator")
	@SequenceGenerator(name = "admin_user_seq_generator", sequenceName = "admin_user_seq", allocationSize = 1)
	private Long id;

	@Column(nullable = false, updatable = false, length = 50)
	private String username;

	/*
	 * updatable = false 가 꼭 필요하다. Hibernate 는 기본으로 UPDATE 문에 "바뀐 열"이 아니라 "updatable 인 모든 열"을 넣는다
	 * (문장을 미리 만들어 재사용하려고). 승인할 때 status 만 바꿔도 password_hash 까지 SET 에 들어가고, 앱 계정은 그 열의
	 * UPDATE 권한이 없어 ORA-41900 으로 실패한다. 비밀번호 변경 기능이 생기면 그때 권한과 함께 푼다.
	 */
	@Column(name = "password_hash", nullable = false, updatable = false, length = 100)
	private String passwordHash;

	/*
	 * EnumType.STRING: DB 에 'TELLER' 같은 이름으로 저장한다. 기본값(ORDINAL)은 0, 1, 2 순번이라 enum 중간에 값을 끼워 넣으면
	 * 이미 저장된 행의 뜻이 조용히 바뀐다(창구가 책임자가 되는 식). 권한이라 특히 위험하다.
	 * updatable = false: 역할을 바꾸는 UPDATE 를 JPA 가 아예 만들지 않는다. DB 권한(R__)도 이 열을 막는다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 10)
	private AdminRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private AdminUserStatus status;

	/** 등록을 요청한 운영자. 서버 설정으로 만든 계정은 null */
	@Column(name = "requested_by", updatable = false, length = 50)
	private String requestedBy;

	@Column(name = "decided_by", length = 50)
	private String decidedBy;

	@Column(name = "decided_at")
	private LocalDateTime decidedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/** 두 책임자가 동시에 결정하면 한쪽만 성공한다(CorrectionRequest 와 같은 낙관적 잠금). */
	@Version
	private long version;

	protected AdminUser() {
	}

	private AdminUser(String username, String passwordHash, AdminRole role, AdminUserStatus status, String requestedBy) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.role = role;
		this.status = status;
		this.requestedBy = requestedBy;
		this.createdAt = DbTime.now();
	}

	/** 서버 설정(.env)으로 만드는 계정. 승인할 사람이 아직 없으므로 처음부터 ACTIVE 다. 이미 해시한 비밀번호를 받는다. */
	static AdminUser bootstrap(String username, String passwordHash, AdminRole role) {
		return new AdminUser(username, passwordHash, role, AdminUserStatus.ACTIVE, null);
	}

	/** 운영자의 등록 요청. 책임자가 승인할 때까지 로그인할 수 없다. */
	static AdminUser requested(String username, String passwordHash, AdminRole role, String requestedBy) {
		return new AdminUser(username, passwordHash, role, AdminUserStatus.PENDING, requestedBy);
	}

	public boolean isActive() {
		return status == AdminUserStatus.ACTIVE;
	}

	/**
	 * @throws AdminAlreadyDecidedException 이미 결정됨
	 * @throws AdminSelfApprovalException   요청한 사람이 결정하려 함
	 */
	void approve(String admin) {
		decide(AdminUserStatus.ACTIVE, admin);
	}

	void reject(String admin) {
		decide(AdminUserStatus.REJECTED, admin);
	}

	private void decide(AdminUserStatus decision, String admin) {
		if (status != AdminUserStatus.PENDING) {
			throw new AdminAlreadyDecidedException(id);
		}
		if (admin.equals(requestedBy)) {
			throw new AdminSelfApprovalException(id);
		}
		this.status = decision;
		this.decidedBy = admin;
		this.decidedAt = DbTime.now();
	}

}
