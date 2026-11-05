package com.corebank.closing;

import com.corebank.ledger.TransactionType;

import java.math.BigDecimal;

/** 일계표 한 줄: 영업일의 거래 종류별 건수와 금액 합계. */
public record DailyClosingTotal(TransactionType type, long count, BigDecimal amount) {
}
