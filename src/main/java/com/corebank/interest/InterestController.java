package com.corebank.interest;

import com.corebank.account.AccountAccessPolicy;
import com.corebank.auth.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 이자 API. 계좌 이자 현황은 계좌 주인(고객)과 관리자가, 적립·결산 실행과 전체 지급 목록은 관리자만 쓴다.
 * 적립과 결산은 매일·매달 자동으로 돌고(InterestScheduler), 이 API 는 수동 실행(재실행)용이다.
 */
@RestController
public class InterestController {

	private final InterestService service;
	private final AccountAccessPolicy accessPolicy;

	public InterestController(InterestService service, AccountAccessPolicy accessPolicy) {
		this.service = service;
		this.accessPolicy = accessPolicy;
	}

	public record AccrualRequest(@NotNull LocalDate businessDate) {
	}

	/** @param month "2026-11" 형식 */
	public record SettlementRequest(@NotNull YearMonth month) {
	}

	/**
	 * 지급 한 건. 금액은 원 단위 정수다.
	 *
	 * @param transactionId 원장의 이자 거래(세후). 이자가 0원이면 null
	 */
	public record Payment(Long id, Long accountId, InterestTrigger trigger, LocalDate periodFrom, LocalDate periodTo,
			int dayCount, long grossAmount, long incomeTax, long localTax, long netAmount, Long transactionId,
			LocalDateTime paidAt) {

		static Payment from(InterestPayment p) {
			return new Payment(p.getId(), p.getAccountId(), p.getTrigger(), p.getPeriodFrom(), p.getPeriodTo(),
					p.getDayCount(), p.getGrossAmount(), p.getIncomeTax(), p.getLocalTax(), p.getNetAmount(),
					p.getLedgerTransactionId(), p.getPaidAt());
		}
	}

	/**
	 * 아직 지급하지 않은 이자.
	 *
	 * @param balanceRateSum Σ(일별 잔액 × 연이율%). 매우 클 수 있어 문자열로 보낸다(관리자 요약 금액과 같은 규칙)
	 */
	public record Unpaid(LocalDate from, LocalDate to, int days, String balanceRateSum, long estimatedGross,
			long estimatedTax, long estimatedNet) {
	}

	/** @param unpaid 어제까지 쌓였지만 아직 지급하지 않은 이자. 없으면 null */
	public record AccountInterestResponse(Long accountId, Unpaid unpaid, List<Payment> payments) {
	}

	public record PaymentPage(List<Payment> content, int page, int size, long totalElements, int totalPages) {
	}

	/** GET /accounts/{id}/interest: 계좌 이자 현황(예상 이자, 지급 내역). 남의 계좌면 404 */
	@GetMapping("/accounts/{id}/interest")
	public AccountInterestResponse accountInterest(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
		accessPolicy.checkAccess(Actor.from(jwt), id);
		InterestService.AccountInterest interest = service.accountInterest(id);
		Unpaid unpaid = interest.unpaid() == null ? null : new Unpaid(interest.unpaid().from(), interest.unpaid().to(),
				interest.unpaid().days(), interest.unpaid().balanceRateSum().toPlainString(),
				interest.estimate().gross(), interest.estimate().tax(), interest.estimate().net());
		return new AccountInterestResponse(id, unpaid, interest.payments().stream().map(Payment::from).toList());
	}

	/** POST /admin/interest/accruals {businessDate}: 하루치 적수 쌓기. 다시 실행해도 두 번 쌓이지 않는다 */
	@PostMapping("/admin/interest/accruals")
	public InterestService.AccrualResult accrue(@Valid @RequestBody AccrualRequest request) {
		return service.accrue(request.businessDate());
	}

	/** POST /admin/interest/settlements {month}: 월 결산(이자 지급). 다시 실행하면 아직 못 받은 계좌만 지급한다 */
	@PostMapping("/admin/interest/settlements")
	public InterestService.SettlementResult settle(@Valid @RequestBody SettlementRequest request) {
		return service.settle(request.month());
	}

	/** GET /admin/interest/payments?page=&size=: 최근 이자 지급 */
	@GetMapping("/admin/interest/payments")
	public PaymentPage payments(
			@RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		Page<InterestPayment> result = service.recentPayments(page, size);
		return new PaymentPage(result.getContent().stream().map(Payment::from).toList(), result.getNumber(),
				result.getSize(), result.getTotalElements(), result.getTotalPages());
	}

}
