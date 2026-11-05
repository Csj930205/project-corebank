package com.corebank.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/** 고객의 계좌 목록(최신 개설순). (customer_id, id) 인덱스를 탄다. */
	java.util.List<Account> findByCustomerIdOrderByIdDesc(Long customerId);

	/** 이 계좌가 이 고객 것인지. 계좌 전체를 읽지 않고 존재 여부만 본다. */
	boolean existsByIdAndCustomerId(Long id, Long customerId);

	/**
	 * 관리자 계좌 검색. 검색어는 계좌번호 앞자리 일치 또는 예금주 이름 부분 일치이고, 값이 null 이면 조건을 빼고 찾는다.
	 * pattern 들은 호출하는 쪽에서 %, _ 를 이스케이프한 LIKE 패턴이다(ESCAPE '\').
	 */
	@Query("""
			SELECT a FROM Account a
			WHERE (:numberPrefix IS NULL OR a.accountNumber LIKE :numberPrefix ESCAPE '\\'
			       OR LOWER(a.ownerName) LIKE :namePattern ESCAPE '\\')
			  AND (:status IS NULL OR a.status = :status)
			""")
	Page<Account> search(@Param("numberPrefix") String numberPrefix, @Param("namePattern") String namePattern,
			@Param("status") AccountStatus status, Pageable pageable);

}
