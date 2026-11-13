package com.corebank.timedeposit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TimeDepositRepository extends JpaRepository<TimeDeposit, Long> {

	/** 이 계좌를 연결계좌로 쓰는 정기예금이 있는지. (payout_account_id, status) 인덱스를 탄다 */
	boolean existsByPayoutAccountIdAndStatus(Long payoutAccountId, TimeDepositStatus status);

	/** 연결계좌 번호만 읽는다. 엔티티를 영속성 컨텍스트에 올리지 않는다(TimeDepositService.lockBoth 참고) */
	@Query("SELECT t.payoutAccountId FROM TimeDeposit t WHERE t.accountId = :id")
	java.util.Optional<Long> findPayoutAccountId(@Param("id") Long id);

	/** 만기가 된 가입 중 정기예금의 번호(오래된 만기부터). 엔티티 대신 번호만 읽고, 한 건씩 잠가 처리한다 */
	@Query("""
			SELECT t.accountId FROM TimeDeposit t
			WHERE t.status = com.corebank.timedeposit.TimeDepositStatus.ACTIVE AND t.maturityDate <= :today
			ORDER BY t.maturityDate, t.accountId
			""")
	List<Long> findDueIds(@Param("today") LocalDate today);

	/**
	 * 관리자 목록. 화면이 계좌번호·예금주·상품 이름을 함께 보여 주므로 계좌와 상품을 같은 쿼리에서 JOIN 으로 읽는다(@EntityGraph).
	 * 둘 다 "하나" 쪽(@OneToOne, @ManyToOne)이라 행 수가 늘지 않아 페이징을 DB 에서 그대로 한다.
	 */
	@EntityGraph(attributePaths = {"account", "product"})
	@Query("""
			SELECT t FROM TimeDeposit t
			WHERE (:status IS NULL OR t.status = :status)
			ORDER BY t.accountId DESC
			""")
	Page<TimeDeposit> search(@Param("status") TimeDepositStatus status, Pageable pageable);

}
