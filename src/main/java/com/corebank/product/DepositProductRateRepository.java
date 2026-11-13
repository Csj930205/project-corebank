package com.corebank.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepositProductRateRepository extends JpaRepository<DepositProductRate, Long> {

	/** 상품의 금리 이력(최신순). (product_id, effective_from, id) 인덱스를 탄다 */
	List<DepositProductRate> findByProductIdOrderByEffectiveFromDescIdDesc(Long productId);

}
