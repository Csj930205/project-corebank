package com.corebank.correction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CorrectionRequestRepository extends JpaRepository<CorrectionRequest, Long> {

	/**
	 * 목록. 대상 거래를 JOIN FETCH 로 함께 읽어, 요청마다 거래를 따로 조회하는 N+1 을 막는다.
	 *
	 * <p>@ManyToOne 을 fetch join 하므로 페이징이 DB 에서 그대로 된다(행 수가 늘지 않는다). 컬렉션(@OneToMany)을 fetch join
	 * 하면 행이 불어나 Hibernate 가 전체를 메모리로 읽은 뒤 자르므로 조심해야 한다.
	 * fetch join 이 들어간 쿼리는 Spring Data 가 COUNT 쿼리를 제대로 만들지 못하므로 countQuery 를 따로 적는다.
	 */
	@Query(value = """
			SELECT r FROM CorrectionRequest r JOIN FETCH r.transaction
			WHERE (:status IS NULL OR r.status = :status)
			ORDER BY r.id DESC
			""",
			countQuery = "SELECT COUNT(r) FROM CorrectionRequest r WHERE (:status IS NULL OR r.status = :status)")
	Page<CorrectionRequest> search(@Param("status") CorrectionStatus status, Pageable pageable);

	@Query("SELECT r FROM CorrectionRequest r JOIN FETCH r.transaction WHERE r.id = :id")
	Optional<CorrectionRequest> findWithTransaction(@Param("id") Long id);

}
