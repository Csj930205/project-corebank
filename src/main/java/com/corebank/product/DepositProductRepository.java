package com.corebank.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 상품 저장소. 타입이 부모(DepositProduct)라서 조회 결과에 두 종류가 섞여 나온다(다형 조회). 각 행은 product_type 값에 따라
 * DemandDepositProduct 나 TimeDepositProduct 로 만들어진다.
 */
public interface DepositProductRepository extends JpaRepository<DepositProduct, Long> {

	List<DepositProduct> findByStatusOrderById(ProductStatus status);

	List<DepositProduct> findAllByOrderById();

	/** 금리를 바꿀 때 잠근다. 두 관리자가 동시에 바꾸면 이력의 마지막 금리와 상품의 지금 금리가 어긋날 수 있다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM DepositProduct p WHERE p.id = :id")
	Optional<DepositProduct> findByIdForUpdate(@Param("id") Long id);

}
