package com.corebank.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 관리자 계정. 비밀번호 원문은 어디에도 남기지 않고 해시만 저장한다.
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

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected AdminUser() {
	}

	private AdminUser(String username, String passwordHash) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.createdAt = LocalDateTime.now();
	}

	/** 이미 해시한 비밀번호로 만든다. 원문을 받지 않아 실수로 원문이 저장될 수 없다. */
	static AdminUser create(String username, String passwordHash) {
		return new AdminUser(username, passwordHash);
	}

}
