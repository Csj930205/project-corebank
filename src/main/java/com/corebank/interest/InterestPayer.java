package com.corebank.interest;

import com.corebank.account.Account;
import com.corebank.ledger.LedgerService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 이자 지급 한 건(잔액·원장·지급 기록·적수 표시). 월 결산 배치의 writer 와 계좌 해지가 함께 쓴다.
 *
 * <p>InterestService 에서 떼어 낸 이유: 결산 배치의 writer 가 지급을 해야 하는데, InterestService 는 결산 배치(Job)를
 * 실행하는 쪽이라 writer 가 InterestService 를 쓰면 InterestService → Job → writer → InterestService 로 빈이 돌고 돈다.
 *
 * <p>호출하는 쪽이 계좌를 잠근 트랜잭션 안에서 부른다(MANDATORY). 트랜잭션 없이 부르면 바로 실패한다.
 */
@Component
public class InterestPayer {

	private final InterestAccruals accruals;
	private final InterestPaymentRepository payments;
	private final LedgerService ledgerService;

	public InterestPayer(InterestAccruals accruals, InterestPaymentRepository payments, LedgerService ledgerService) {
		this.accruals = accruals;
		this.payments = payments;
		this.ledgerService = ledgerService;
	}

	/** upTo 까지 쌓인 적수를 지금 읽어 지급한다(해지). 적수가 없으면 비어 있다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<InterestPayment> payUnpaid(Account account, LocalDate upTo, InterestTrigger trigger) {
		return accruals.unpaid(account.getId(), upTo)
				.map(unpaid -> pay(account, upTo, unpaid, InterestCalculator.of(unpaid.balanceRateSum()), trigger, null));
	}

	/**
	 * 미리 계산한 이자를 지급한다(결산 배치). 계산에 쓴 적수가 그 사이 바뀌었으면 markPaid 의 행 수 검사에서 실패한다.
	 *
	 * <p>순서가 중요하다.
	 * <ol>
	 *   <li>잔액을 늘리고 원장에 기록한다. 이자가 0원이면 원장 거래는 없다(원장 금액은 1원 이상).</li>
	 *   <li>지급 기록을 saveAndFlush 로 <b>바로 INSERT</b> 한다. 다음 단계의 UPDATE 는 JdbcTemplate 로 나가는데,
	 *       JdbcTemplate 은 JPA 가 아직 DB 에 보내지 않은(영속성 컨텍스트에만 있는) 행을 모른다.
	 *       flush 하지 않으면 payment_id 외래키가 가리킬 행이 DB 에 없어 실패한다.</li>
	 *   <li>적수에 지급 번호를 채운다. 바뀐 행 수가 합계를 낸 행 수와 다르면(그 사이 적수가 더 쌓였거나 다른 곳에서 먼저
	 *       지급함) 예외를 던져 트랜잭션을 통째로 되돌린다. 돈이 두 번 나가는 일을 막는 마지막 확인이다.</li>
	 * </ol>
	 *
	 * @param jobExecutionId 결산 배치 실행 번호. 해지면 null
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public InterestPayment pay(Account account, LocalDate upTo, InterestAccruals.Unpaid unpaid,
			InterestCalculator.Interest interest, InterestTrigger trigger, Long jobExecutionId) {
		Long ledgerTransactionId = null;
		if (interest.gross() > 0) {
			account.deposit(interest.net());
			ledgerTransactionId = ledgerService.recordInterest(account.getId(), interest.gross(), interest.tax(),
					account.getBalance()).getId();
		}
		InterestPayment payment = payments.saveAndFlush(
				InterestPayment.of(account.getId(), trigger, unpaid, interest, ledgerTransactionId, jobExecutionId));
		int marked = accruals.markPaid(account.getId(), upTo, payment.getId());
		if (marked != unpaid.days()) {
			throw new IllegalStateException("적수가 지급 중에 바뀌었습니다. accountId=" + account.getId()
					+ ", expected=" + unpaid.days() + ", marked=" + marked);
		}
		return payment;
	}

}
