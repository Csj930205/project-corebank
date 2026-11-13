package com.corebank.timedeposit;

import com.corebank.account.Account;
import com.corebank.account.AccountStatus;
import com.corebank.interest.InterestPayment;
import com.corebank.interest.InterestTrigger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정기예금 응답.
 *
 * @param annualRate 약정이율(%)
 * @param atMaturity 만기까지 두면 받을 이자
 * @param quote      오늘 해지하면 받을 이자와 금액. 해지한 예금이면 null
 * @param settlement 해지 결과. 가입 중이면 null
 */
public record TimeDepositResponse(
		Long accountId,
		String accountNumber,
		String ownerName,
		Long customerId,
		long balance,
		AccountStatus accountStatus,
		Long productId,
		String productName,
		TimeDepositStatus status,
		long principal,
		BigDecimal annualRate,
		int termMonths,
		LocalDate startDate,
		LocalDate maturityDate,
		Long payoutAccountId,
		Estimate atMaturity,
		Estimate quote,
		Settlement settlement) {

	/**
	 * 이자 계산 결과.
	 *
	 * @param trigger     MATURITY(만기) 또는 EARLY_TERM(중도해지)
	 * @param appliedRate 적용 이율(%)
	 * @param days        이자 붙는 일수
	 * @param payout      연결계좌로 갈 금액(원금 + 세후 이자)
	 */
	public record Estimate(InterestTrigger trigger, BigDecimal appliedRate, int days, long grossInterest, long tax,
			long netInterest, long payout) {

		static Estimate of(TimeDepositInterest.Settlement settlement, long principal) {
			return new Estimate(settlement.trigger(), settlement.appliedRate(), settlement.days(),
					settlement.interest().gross(), settlement.interest().tax(), settlement.interest().net(),
					settlement.payout(principal));
		}
	}

	/** @param interestPaymentId 이자 지급 기록. 이자 붙은 날이 0일이면 null */
	public record Settlement(TimeDepositStatus status, BigDecimal appliedRate, Long interestPaymentId, long grossInterest,
			long tax, long netInterest, long payout, Long payoutTransferId, LocalDateTime settledAt) {

		static Settlement of(TimeDepositService.Settled settled) {
			TimeDeposit deposit = settled.deposit();
			InterestPayment payment = settled.payment();
			return new Settlement(deposit.getStatus(), deposit.getAppliedRate(), deposit.getInterestPaymentId(),
					payment == null ? 0 : payment.getGrossAmount(),
					payment == null ? 0 : payment.getIncomeTax() + payment.getLocalTax(),
					payment == null ? 0 : payment.getNetAmount(),
					settled.payout(), deposit.getPayoutTransferId(), deposit.getSettledAt());
		}
	}

	static TimeDepositResponse from(TimeDepositService.Detail detail) {
		TimeDeposit deposit = detail.deposit();
		return of(deposit, detail.account(), detail.productName(), Estimate.of(detail.atMaturity(), deposit.getPrincipal()),
				detail.quote() == null ? null : Estimate.of(detail.quote(), deposit.getPrincipal()),
				detail.settled() == null ? null : Settlement.of(detail.settled()));
	}

	/** 관리자 목록. quote 는 없다(목록은 계약만 보여 준다). 계좌·상품은 목록 쿼리가 함께 읽었다(@EntityGraph) */
	static TimeDepositResponse summary(TimeDeposit deposit) {
		return of(deposit, deposit.getAccount(), deposit.getProduct().getName(),
				Estimate.of(deposit.atMaturity(), deposit.getPrincipal()), null, null);
	}

	private static TimeDepositResponse of(TimeDeposit deposit, Account account, String productName, Estimate atMaturity,
			Estimate quote, Settlement settlement) {
		return new TimeDepositResponse(account.getId(), account.getAccountNumber(), account.getOwnerName(),
				account.getCustomerId(), account.getBalance(), account.getStatus(), deposit.getProduct().getId(),
				productName, deposit.getStatus(), deposit.getPrincipal(), deposit.getAnnualRate(),
				deposit.getTermMonths(), deposit.getStartDate(), deposit.getMaturityDate(),
				deposit.getPayoutAccountId(), atMaturity, quote, settlement);
	}

}
