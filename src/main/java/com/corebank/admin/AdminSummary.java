package com.corebank.admin;

/**
 * 관리자 첫 화면 요약.
 *
 * @param totalBalance 전체 계좌 잔액 합계(원). long 범위를 넘을 수 있고 JS number 로는 정확하지 않아 문자열로 보낸다.
 */
public record AdminSummary(
		long accountCount,
		long activeAccountCount,
		long closedAccountCount,
		String totalBalance,
		long transactionCount,
		long todayTransactionCount,
		long transferCount,
		LedgerCheck ledger) {

	/**
	 * 원장 점검(대사) 결과. 셋 다 0 이면 balanced = true.
	 *
	 * @param debitMinusCredit   전체 차변 합 - 대변 합
	 * @param clearingBalance    이체 가계정 잔액 (반쪽 이체가 있으면 0 이 아님)
	 * @param mismatchedAccounts 계좌 잔액과 원장 분개 합계가 다른 계좌 수
	 */
	public record LedgerCheck(boolean balanced, String debitMinusCredit, String clearingBalance, long mismatchedAccounts) {
	}

}
