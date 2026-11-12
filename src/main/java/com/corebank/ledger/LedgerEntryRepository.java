package com.corebank.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	/** 거래 한 건의 분개. 역분개를 만들 때 원래 분개를 그대로 뒤집으려고 읽는다. */
	List<LedgerEntry> findByTransactionIdOrderById(Long transactionId);

}
