package com.corebank.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 성공한 요청의 멱등성 키 기록. 수정하지 않는다.
 */
@Getter
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "idempotency_record_seq_generator")
	@SequenceGenerator(name = "idempotency_record_seq_generator", sequenceName = "idempotency_record_seq", allocationSize = 1)
	private Long id;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
	private String key;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private IdempotentOperation operation;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 200)
	private String requestFingerprint;

	@Column(name = "resource_id", nullable = false, updatable = false)
	private Long resourceId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected IdempotencyRecord() {
	}

	IdempotencyRecord(IdempotencyKey key, IdempotentOperation operation, String requestFingerprint, Long resourceId) {
		this.key = key.value();
		this.operation = operation;
		this.requestFingerprint = requestFingerprint;
		this.resourceId = resourceId;
		this.createdAt = LocalDateTime.now();
	}

}
