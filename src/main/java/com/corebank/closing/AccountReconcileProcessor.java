package com.corebank.closing;

import org.springframework.batch.infrastructure.item.ItemProcessor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 계좌 한 개를 대사한다. DB 를 다시 읽지 않고 리더가 가져온 값만 비교하므로 단위 테스트가 쉽다.
 *
 * <p>두 가지를 본다.
 * <ol>
 *   <li>현재 잔액 = 지금까지의 원장 합계. 잔액 컬럼이 원장 없이 바뀌었는지 본다.</li>
 *   <li>마감 시점 마지막 거래의 거래 후 잔액 = 마감 시점까지의 원장 합계. 마감 시점에도 계좌가 원장과 맞았는지 본다.
 *       과거 날짜를 다시 마감해도 그날 기준으로 검사할 수 있는 것은 이 항목이다.</li>
 * </ol>
 *
 * <p>불일치가 없으면 null 을 돌려준다. Spring Batch 는 null 을 "걸러 냄(filter)" 으로 세고 writer 에 넘기지 않는다.
 * 그래서 실행 이력의 FILTER_COUNT 가 정상 계좌 수가 된다.
 */
public class AccountReconcileProcessor implements ItemProcessor<AccountSnapshot, AccountReconciliation> {

	@Override
	public AccountReconciliation process(AccountSnapshot account) {
		List<ClosingMismatch> mismatches = new ArrayList<>(2);

		if (account.balance().compareTo(account.ledgerTotal()) != 0) {
			mismatches.add(ClosingMismatch.ofAccount(MismatchType.BALANCE_LEDGER_MISMATCH, account.id(),
					account.ledgerTotal(), account.balance(),
					"계좌 " + account.accountNumber() + ": 현재 잔액(actual)이 원장 합계(expected)와 다릅니다."));
		}

		// 마감 시점까지 거래가 없던 계좌는 잔액이 0이어야 한다.
		BigDecimal snapshot = ClosingRepository.zeroIfNull(account.balanceAfterAtCutoff());
		if (snapshot.compareTo(account.ledgerAtCutoff()) != 0) {
			mismatches.add(ClosingMismatch.ofAccount(MismatchType.SNAPSHOT_MISMATCH, account.id(),
					account.ledgerAtCutoff(), snapshot,
					"계좌 " + account.accountNumber() + ": 마감 시점 마지막 거래의 거래 후 잔액(actual)이 "
							+ "마감 시점 원장 합계(expected)와 다릅니다."));
		}

		return mismatches.isEmpty() ? null : new AccountReconciliation(account.id(), mismatches);
	}

}
