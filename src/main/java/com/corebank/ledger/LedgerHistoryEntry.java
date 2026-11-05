package com.corebank.ledger;

/**
 * 거래내역 한 줄. 원장 거래와, 이체일 때 상대 계좌 id 를 함께 담는다.
 *
 * @param counterpartyAccountId 이체 상대 계좌 id. 입출금은 null
 */
public record LedgerHistoryEntry(LedgerTransaction transaction, Long counterpartyAccountId) {
}
