package com.corebank.closing;

import com.corebank.ledger.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 일 마감 관리자 API. /admin/** 이라 관리자 토큰이 있어야 한다(SecurityConfig).
 *
 * <p>금액 합계는 long 범위를 넘을 수 있고 JS number 로는 정확하지 않아 문자열로 보낸다(관리자 요약 API 와 같은 규칙).
 */
@RestController
@RequestMapping("/admin/closings")
public class ClosingController {

	private final ClosingService closingService;

	public ClosingController(ClosingService closingService) {
		this.closingService = closingService;
	}

	public record RunRequest(@NotNull LocalDate businessDate) {
	}

	public record ClosingSummary(
			Long id,
			LocalDate businessDate,
			ClosingStatus status,
			ClosingTrigger trigger,
			String requestedBy,
			Long jobExecutionId,
			Long accountCount,
			String depositLiability,
			Long mismatchCount,
			LocalDateTime startedAt,
			LocalDateTime finishedAt,
			String errorMessage) {

		static ClosingSummary from(DailyClosing c) {
			return new ClosingSummary(c.id(), c.businessDate(), c.status(), c.trigger(), c.requestedBy(),
					c.jobExecutionId(), c.accountCount(), plain(c.depositLiability()), c.mismatchCount(),
					c.startedAt(), c.finishedAt(), c.errorMessage());
		}
	}

	public record Total(TransactionType type, long count, String amount) {
	}

	public record Mismatch(Long id, MismatchType type, Long accountId, Long transactionId, Long transferId,
			String expected, String actual, String detail) {
	}

	/**
	 * 상세 응답.
	 *
	 * @param mismatches 불일치 내역. 많으면 앞의 일부만 담고, 전체 건수는 closing.mismatchCount 다.
	 */
	public record ClosingDetail(ClosingSummary closing, List<Total> totals, List<Mismatch> mismatches) {
	}

	public record ClosingPage(List<ClosingSummary> content, int page, int size, long totalElements, int totalPages) {
	}

	/**
	 * POST /admin/closings {businessDate}: 수동 마감. 배치가 끝날 때까지 기다렸다가 결과를 201 로 돌려준다.
	 * 마감 실행 기록이 새로 생기므로 201 이고, 불일치가 있거나 배치가 실패해도 실행 기록은 생기므로 201 이다(status 로 구분).
	 */
	@PostMapping
	public ResponseEntity<ClosingDetail> run(@Valid @RequestBody RunRequest request, @AuthenticationPrincipal Jwt jwt) {
		DailyClosing closing = closingService.run(request.businessDate(), ClosingTrigger.MANUAL, jwt.getSubject());
		return ResponseEntity.created(URI.create("/admin/closings/" + closing.id())).body(detail(closing));
	}

	/** GET /admin/closings?page=0&size=20: 최근 실행 순 목록 */
	@GetMapping
	public ClosingPage list(
			@RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		long total = closingService.count();
		List<ClosingSummary> content = closingService.list(page, size).stream().map(ClosingSummary::from).toList();
		return new ClosingPage(content, page, size, total, (int) ((total + size - 1) / size));
	}

	/** GET /admin/closings/{id}: 일계표와 불일치 내역을 포함한 상세 */
	@GetMapping("/{id}")
	public ClosingDetail get(@PathVariable long id) {
		return detail(closingService.get(id));
	}

	private ClosingDetail detail(DailyClosing closing) {
		List<Total> totals = closingService.totals(closing.id()).stream()
				.map(t -> new Total(t.type(), t.count(), plain(t.amount())))
				.toList();
		List<Mismatch> mismatches = closingService.mismatches(closing.id()).stream()
				.map(m -> new Mismatch(m.id(), m.type(), m.accountId(), m.transactionId(), m.transferId(),
						plain(m.expected()), plain(m.actual()), m.detail()))
				.toList();
		return new ClosingDetail(ClosingSummary.from(closing), totals, mismatches);
	}

	private static String plain(BigDecimal value) {
		return value == null ? null : value.toPlainString();
	}

}
