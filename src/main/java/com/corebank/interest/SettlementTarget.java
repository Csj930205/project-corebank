package com.corebank.interest;

/**
 * 결산 배치 reader 가 읽는 한 줄: 결산할 적수가 남은 계좌와 그 적수 요약.
 *
 * @param closed 읽은 시점에 해지된 계좌. 해지할 때 이자를 이미 받았으므로 processor 가 걸러 낸다
 */
public record SettlementTarget(Long accountId, boolean closed, InterestAccruals.Unpaid unpaid) {
}
