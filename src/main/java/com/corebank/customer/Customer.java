package com.corebank.customer;

import com.corebank.common.DbTime;
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
 * 고객 계정. 관리자(AdminUser)와 마찬가지로 비밀번호는 해시만 저장한다.
 */
@Getter
@Entity
@Table(name = "customer")
public class Customer {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_seq_generator")
	@SequenceGenerator(name = "customer_seq_generator", sequenceName = "customer_seq", allocationSize = 1)
	private Long id;

	@Column(nullable = false, updatable = false, length = 30)
	private String username;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected Customer() {
	}

	private Customer(String username, String passwordHash, String name) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.name = name;
		this.createdAt = DbTime.now();
	}

	/** 이미 해시한 비밀번호로 만든다. 원문을 받지 않아 실수로 원문이 저장될 수 없다. */
	static Customer create(String username, String passwordHash, String name) {
		return new Customer(username, passwordHash, name);
	}

}
