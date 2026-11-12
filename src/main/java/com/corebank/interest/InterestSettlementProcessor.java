package com.corebank.interest;

import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * 계좌 한 개의 이자를 계산한다. DB 를 보지 않는 순수 계산이라 단위 테스트가 쉽다(일 마감의 AccountReconcileProcessor 와 같다).
 *
 * <p>해지된 계좌는 null 을 돌려준다. Spring Batch 는 null 을 "걸러 냄(filter)" 으로 세고 writer 에 넘기지 않는다.
 * 그래서 실행 이력의 FILTER_COUNT 가 "해지돼서 건너뛴 계좌 수" 가 된다.
 *
 * <p>계산을 writer 가 아니라 여기서 하는 이유: chunk 쓰기가 실패해 한 건씩 다시 쓰는(scan) 동안에도 processor 결과는
 * 다시 계산하지 않고 재사용된다. 계산과 쓰기를 나누면 "무엇을 줄지" 와 "어떻게 안전하게 줄지" 를 따로 시험할 수 있다.
 */
public class InterestSettlementProcessor implements ItemProcessor<SettlementTarget, InterestSettlement> {

	@Override
	public InterestSettlement process(SettlementTarget target) {
		if (target.closed()) {
			return null;
		}
		return new InterestSettlement(target.accountId(), target.unpaid(),
				InterestCalculator.of(target.unpaid().balanceRateSum()));
	}

}
