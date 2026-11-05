package com.corebank.transfer;

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
 * 이체 한 건. 원장의 출금 쪽 거래와 입금 쪽 거래를 하나로 묶는 번호 역할을 한다.
 * 원장과 마찬가지로 기록 후 수정하지 않는다.
 */
@Getter
@Entity
@Table(name = "transfer")
public class Transfer {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "transfer_seq_generator")
	@SequenceGenerator(name = "transfer_seq_generator", sequenceName = "transfer_seq", allocationSize = 1)
	private Long id;

	@Column(name = "from_account_id", nullable = false, updatable = false)
	private Long fromAccountId;

	@Column(name = "to_account_id", nullable = false, updatable = false)
	private Long toAccountId;

	@Column(nullable = false, updatable = false)
	private long amount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected Transfer() {
	}

	private Transfer(Long fromAccountId, Long toAccountId, long amount) {
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.amount = amount;
		this.createdAt = LocalDateTime.now();
	}

	static Transfer of(Long fromAccountId, Long toAccountId, long amount) {
		return new Transfer(fromAccountId, toAccountId, amount);
	}

}
