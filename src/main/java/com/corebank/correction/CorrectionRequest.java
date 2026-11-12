package com.corebank.correction;

import com.corebank.common.DbTime;
import com.corebank.ledger.LedgerTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 거래 정정 요청. 한 관리자가 요청하고(maker) 다른 관리자가 승인하거나 반려한다(checker).
 *
 * <p>상태는 REQUESTED → APPROVED 또는 REJECTED 로 한 번만 바뀐다. 바꾸는 메서드(approve, reject)가 이 규칙을 지킨다.
 */
@Getter
@Entity
@Table(name = "correction_request")
public class CorrectionRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "correction_request_seq_generator")
	@SequenceGenerator(name = "correction_request_seq_generator", sequenceName = "correction_request_seq", allocationSize = 1)
	private Long id;

	/*
	 * 다른 엔티티는 id 값만 갖는데(Account.customerId 등) 여기는 연관관계(@ManyToOne)로 둔다.
	 * - 요청 화면은 항상 대상 거래(종류, 금액, 계좌)를 함께 보여 준다. 목록 쿼리에서 JOIN FETCH 로 한 번에 읽는다.
	 * - 원장 거래는 바뀌지 않는 기록이라, 엔티티를 이어 두어도 "한쪽을 바꾸면 다른 쪽이 영향받는" 결합이 생기지 않는다.
	 * LAZY: @ManyToOne 의 기본값은 EAGER 다. EAGER 면 요청 하나를 읽을 때마다 거래를 따로 조회하는 쿼리가 숨어서 나간다(N+1).
	 * 필요한 곳에서만 JOIN FETCH 로 가져오도록 LAZY 로 바꾼다.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "transaction_id", nullable = false, updatable = false)
	private LedgerTransaction transaction;

	@Column(nullable = false, updatable = false, length = 500)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private CorrectionStatus status;

	@Column(name = "requested_by", nullable = false, updatable = false, length = 50)
	private String requestedBy;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private LocalDateTime requestedAt;

	@Column(name = "decided_by", length = 50)
	private String decidedBy;

	@Column(name = "decided_at")
	private LocalDateTime decidedAt;

	@Column(name = "decision_comment", length = 500)
	private String decisionComment;

	/*
	 * 낙관적 락. Hibernate 가 UPDATE 를 "... WHERE id = ? AND version = ?" 로 보내고 version 을 1 올린다.
	 * 그 사이 다른 트랜잭션이 먼저 바꿨다면 바뀐 행이 0건이라 예외(OptimisticLockException)가 난다.
	 * 승인과 반려가 동시에 들어와도 하나만 성공한다.
	 */
	@Version
	private long version;

	protected CorrectionRequest() {
	}

	private CorrectionRequest(LedgerTransaction transaction, String reason, String requestedBy) {
		this.transaction = transaction;
		this.reason = reason;
		this.requestedBy = requestedBy;
		this.requestedAt = DbTime.now();
		this.status = CorrectionStatus.REQUESTED;
	}

	static CorrectionRequest of(LedgerTransaction transaction, String reason, String requestedBy) {
		return new CorrectionRequest(transaction, reason, requestedBy);
	}

	/**
	 * 승인한다. 역분개 기록은 서비스가 한다.
	 *
	 * @throws CorrectionConflictException 이미 결정된 요청
	 * @throws SelfApprovalException       요청한 사람이 결정하려 할 때
	 */
	void approve(String admin, String comment) {
		decide(CorrectionStatus.APPROVED, admin, comment);
	}

	void reject(String admin, String comment) {
		decide(CorrectionStatus.REJECTED, admin, comment);
	}

	private void decide(CorrectionStatus decision, String admin, String comment) {
		if (status != CorrectionStatus.REQUESTED) {
			throw CorrectionConflictException.alreadyDecided(id);
		}
		if (requestedBy.equals(admin)) {
			throw new SelfApprovalException(id);
		}
		this.status = decision;
		this.decidedBy = admin;
		this.decidedAt = DbTime.now();
		this.decisionComment = comment;
	}

}
