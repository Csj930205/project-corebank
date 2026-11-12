package com.corebank.interest;

/** 결산 배치 processor 의 결과: 계산을 마친 한 계좌의 이자. writer 가 이대로 지급한다. */
public record InterestSettlement(Long accountId, InterestAccruals.Unpaid unpaid, InterestCalculator.Interest interest) {
}
