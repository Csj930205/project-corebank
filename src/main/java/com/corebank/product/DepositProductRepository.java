package com.corebank.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepositProductRepository extends JpaRepository<DepositProduct, Long> {

	List<DepositProduct> findByStatusOrderById(ProductStatus status);

	List<DepositProduct> findAllByOrderById();

}
