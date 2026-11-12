package com.corebank.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 계좌 DB 접근 계층. JpaRepository 를 상속하면 save, findById 같은 기본 메서드는 구현이 자동으로 만들어진다.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

	/** 계좌번호용 일련번호를 받는다. NEXTVAL 은 Oracle 문법이라 네이티브 SQL 로 작성했다. */
	@Query(value = "SELECT account_number_seq.NEXTVAL FROM dual", nativeQuery = true)
	long nextAccountNumberSequence();

	/**
	 * 계좌를 잠그고 조회한다. SELECT ... FOR UPDATE 가 실행되어, 트랜잭션이 끝날 때까지 같은 계좌의 다른 거래는 기다린다.
	 * 동시 입금이 서로의 잔액을 덮어쓰는 문제를 막는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id") Long id);

	/** 계좌번호로 찾는다. 계좌번호 유니크 제약(uk_account_number)의 인덱스를 탄다. */
	Optional<Account> findByAccountNumber(String accountNumber);

	/**
	 * 고객의 계좌 목록(최신 개설순). (customer_id, id) 인덱스를 탄다.
	 * @EntityGraph: 상품(LAZY)을 같은 쿼리에서 JOIN 으로 함께 읽는다. 없으면 응답을 만들며 상품 이름을 읽을 때 계좌마다
	 * 상품 조회가 한 번씩 더 나간다(N+1). JPQL 의 JOIN FETCH 와 같은 효과를, 메서드 이름 쿼리를 그대로 둔 채 얻는다.
	 */
	@EntityGraph(attributePaths = "product")
	java.util.List<Account> findByCustomerIdOrderByIdDesc(Long customerId);

	/** 이 계좌가 이 고객 것인지. 계좌 전체를 읽지 않고 존재 여부만 본다. */
	boolean existsByIdAndCustomerId(Long id, Long customerId);

	/**
	 * 관리자 계좌 검색. 검색어는 계좌번호 앞자리 일치 또는 예금주 이름 부분 일치이고, 값이 null 이면 조건을 빼고 찾는다.
	 * pattern 들은 호출하는 쪽에서 %, _ 를 이스케이프한 LIKE 패턴이다(ESCAPE '\').
	 * 개설 시각은 openedFrom 이상 openedBefore 미만, 잔액은 minBalance 이상 maxBalance 이하다.
	 * 상품은 @EntityGraph 로 함께 읽는다(N+1 방지). @ManyToOne 이라 행 수가 늘지 않아 페이징이 DB 에서 그대로 된다.
	 */
	@EntityGraph(attributePaths = "product")
	@Query("""
			SELECT a FROM Account a
			WHERE (:numberPrefix IS NULL OR a.accountNumber LIKE :numberPrefix ESCAPE '\\'
			       OR LOWER(a.ownerName) LIKE :namePattern ESCAPE '\\')
			  AND (:status IS NULL OR a.status = :status)
			  AND (:openedFrom IS NULL OR a.createdAt >= :openedFrom)
			  AND (:openedBefore IS NULL OR a.createdAt < :openedBefore)
			  AND (:minBalance IS NULL OR a.balance >= :minBalance)
			  AND (:maxBalance IS NULL OR a.balance <= :maxBalance)
			""")
	Page<Account> search(@Param("numberPrefix") String numberPrefix, @Param("namePattern") String namePattern,
			@Param("status") AccountStatus status,
			@Param("openedFrom") LocalDateTime openedFrom, @Param("openedBefore") LocalDateTime openedBefore,
			@Param("minBalance") Long minBalance, @Param("maxBalance") Long maxBalance, Pageable pageable);

}
