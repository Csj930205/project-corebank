package com.corebank.interest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InterestPaymentRepository extends JpaRepository<InterestPayment, Long> {

	List<InterestPayment> findTop12ByAccountIdOrderByIdDesc(Long accountId);

	Page<InterestPayment> findAllByOrderByIdDesc(Pageable pageable);

	/** 결산 배치 한 번의 지급 합계. 지급이 없으면 0 들이다. */
	record Totals(long paid, long gross, long tax, long net) {
	}

	/**
	 * JPQL 생성자 표현식(SELECT new ...)으로 집계 결과를 바로 record 로 받는다. 엔티티를 모두 읽어 자바에서 더하지 않는다.
	 * SUM 은 행이 없으면 null 이라 COALESCE 로 0 을 넣는다.
	 */
	@Query("""
			SELECT new com.corebank.interest.InterestPaymentRepository$Totals(
			    COUNT(p), COALESCE(SUM(p.grossAmount), 0), COALESCE(SUM(p.incomeTax + p.localTax), 0),
			    COALESCE(SUM(p.netAmount), 0))
			FROM InterestPayment p WHERE p.jobExecutionId = :jobExecutionId
			""")
	Totals totalsByJobExecution(@Param("jobExecutionId") Long jobExecutionId);

}
