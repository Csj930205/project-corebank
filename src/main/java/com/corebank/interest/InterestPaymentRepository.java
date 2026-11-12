package com.corebank.interest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterestPaymentRepository extends JpaRepository<InterestPayment, Long> {

	List<InterestPayment> findTop12ByAccountIdOrderByIdDesc(Long accountId);

	Page<InterestPayment> findAllByOrderByIdDesc(Pageable pageable);

}
