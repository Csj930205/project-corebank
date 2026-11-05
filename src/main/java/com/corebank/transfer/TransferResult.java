package com.corebank.transfer;

/**
 * 이체 처리 결과. 이체 기록과 함께 두 계좌의 이체 후 잔액을 돌려준다.
 */
public record TransferResult(Transfer transfer, long fromBalanceAfter, long toBalanceAfter) {
}
