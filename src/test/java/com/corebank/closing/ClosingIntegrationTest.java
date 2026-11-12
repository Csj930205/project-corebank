package com.corebank.closing;

import com.corebank.support.LedgerTampering;
import com.corebank.support.SchemaOwner;
import com.corebank.TestcontainersConfiguration;
import com.corebank.account.AccountService;
import com.corebank.ledger.TransactionType;
import com.corebank.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일 마감 대사 배치 통합 테스트. 실제 Oracle 에서 배치를 끝까지 돌린다. Docker 가 필요하다.
 *
 * <p>테스트 데이터는 모두 "오늘" 생기는데 오늘은 마감할 수 없다. 그래서 이 테스트의 시계는 하루 뒤로 맞춰,
 * 오늘을 "끝난 영업일" 로 마감한다.
 *
 * <p>불일치 테스트는 SQL 로 원장이나 잔액을 일부러 틀어 놓고 배치가 찾아내는지 본다. 같은 DB 를 다른 테스트와 함께 쓰므로
 * 틀어 놓은 값은 finally 에서 반드시 되돌린다. 원장과 이체는 DB 트리거가 수정·삭제를 막으므로(V12), 조작과 되돌리기만
 * LedgerTampering 으로 트리거를 잠시 끄고 한다. 대사 배치는 바로 이런 "트리거를 우회한 변경" 을 찾는 장치다.
 */
@Import({TestcontainersConfiguration.class, ClosingIntegrationTest.TomorrowClock.class})
@SpringBootTest
@AutoConfigureMockMvc
class ClosingIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class TomorrowClock {

		@Bean
		@Primary
		Clock tomorrowClock() {
			return Clock.offset(Clock.systemDefaultZone(), Duration.ofDays(1));
		}
	}

	private static final LocalDate TODAY = LocalDate.now();

	@Autowired
	private ClosingService closingService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private TransferService transferService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SchemaOwner owner;

	@Autowired
	private MockMvc mockMvc;

	private Long openWithBalance(String name, long balance) {
		Long id = accountService.open(name).getId();
		if (balance > 0) {
			accountService.deposit(id, balance, newKey());
		}
		return id;
	}

	private DailyClosing closeToday() {
		return closingService.run(TODAY, ClosingTrigger.MANUAL, "test-admin");
	}

	private List<ClosingMismatch> mismatches(DailyClosing closing) {
		return closingService.mismatches(closing.id());
	}

	// ───────────────────────── 정상 ─────────────────────────

	/**
	 * 정상 거래만 있으면 BALANCED 이고, 일계표와 예금 잔액 합계가 원장과 맞으며, Spring Batch 실행 이력도 남는다.
	 */
	@Test
	void cleanLedgerIsBalanced() {
		Long a = openWithBalance("마감정상A", 10_000);
		Long b = openWithBalance("마감정상B", 5_000);
		accountService.withdraw(a, 1_000, newKey());
		transferService.transfer(a, b, 2_000, newKey());
		accountService.close(b, newKey());

		DailyClosing closing = closeToday();

		assertThat(closing.status()).isEqualTo(ClosingStatus.BALANCED);
		assertThat(closing.mismatchCount()).isZero();
		assertThat(closing.finishedAt()).isNotNull();
		assertThat(closing.accountCount()).isEqualTo(
				jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account", Long.class));
		assertThat(closing.depositLiability()).isEqualByComparingTo(
				jdbcTemplate.queryForObject("SELECT SUM(balance) FROM account", BigDecimal.class));

		// 일계표는 오늘 원장 거래를 종류별로 센 값과 같다.
		Map<TransactionType, DailyClosingTotal> totals = closingService.totals(closing.id()).stream()
				.collect(java.util.stream.Collectors.toMap(DailyClosingTotal::type, t -> t));
		for (TransactionType type : List.of(TransactionType.DEPOSIT, TransactionType.WITHDRAWAL,
				TransactionType.TRANSFER_OUT, TransactionType.TRANSFER_IN, TransactionType.CLOSURE_PAYOUT)) {
			assertThat(totals.get(type).count()).as(type.name()).isEqualTo(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM ledger_transaction WHERE transaction_type = ?", Long.class, type.name()));
		}

		// Spring Batch 실행 이력: 잡은 COMPLETED, 계좌 대사 단계는 모든 계좌를 읽고 전부 걸러 냈다(불일치 0).
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM BATCH_JOB_EXECUTION WHERE job_execution_id = ?", String.class, closing.jobExecutionId()))
				.isEqualTo("COMPLETED");
		Map<String, Object> step = jdbcTemplate.queryForMap("""
				SELECT read_count, filter_count, write_count FROM BATCH_STEP_EXECUTION
				WHERE job_execution_id = ? AND step_name = 'accountReconcileStep'
				""", closing.jobExecutionId());
		assertThat(((Number) step.get("READ_COUNT")).longValue()).isEqualTo(closing.accountCount());
		assertThat(((Number) step.get("FILTER_COUNT")).longValue()).isEqualTo(closing.accountCount());
		assertThat(((Number) step.get("WRITE_COUNT")).longValue()).isZero();
	}

	/** 과거 날짜는 그날 기준으로 대사한다. 어제는 계좌도 거래도 없었다(테스트 데이터는 모두 오늘 생겼다). */
	@Test
	void pastDateUsesThatDaysCutoff() {
		openWithBalance("어제기준", 10_000);

		DailyClosing closing = closingService.run(TODAY.minusDays(1), ClosingTrigger.MANUAL, "test-admin");

		assertThat(closing.status()).isEqualTo(ClosingStatus.BALANCED);
		assertThat(closing.accountCount()).isZero();
		assertThat(closing.depositLiability()).isEqualByComparingTo("0");
		assertThat(closingService.totals(closing.id())).isEmpty();
	}

	/** 같은 날을 다시 마감하면 새 실행이 생기고 이전 실행도 남는다. */
	@Test
	void rerunCreatesNewClosing() {
		DailyClosing first = closeToday();
		DailyClosing second = closeToday();

		assertThat(second.id()).isNotEqualTo(first.id());
		assertThat(second.jobExecutionId()).isNotEqualTo(first.jobExecutionId());
		assertThat(closingService.get(first.id()).status()).isEqualTo(first.status());
	}

	// ───────────────────────── 불일치 찾기 ─────────────────────────

	/** 잔액 컬럼만 1원 바꾸면 그 계좌의 현재 잔액 불일치를 찾는다. 원장은 그대로라 시점 불일치는 아니다. */
	@Test
	void detectsBalanceChangedWithoutLedger() {
		Long id = openWithBalance("잔액조작", 10_000);
		jdbcTemplate.update("UPDATE account SET balance = balance + 1 WHERE id = ?", id);
		try {
			DailyClosing closing = closeToday();

			assertThat(closing.status()).isEqualTo(ClosingStatus.UNBALANCED);
			assertThat(mismatches(closing)).filteredOn(m -> id.equals(m.accountId()))
					.singleElement().satisfies(m -> {
						assertThat(m.type()).isEqualTo(MismatchType.BALANCE_LEDGER_MISMATCH);
						assertThat(m.expected()).isEqualByComparingTo("10000");
						assertThat(m.actual()).isEqualByComparingTo("10001");
					});
		} finally {
			jdbcTemplate.update("UPDATE account SET balance = balance - 1 WHERE id = ?", id);
		}
	}

	/** 마지막 거래의 거래 후 잔액을 바꾸면 시점 불일치를 찾는다. */
	@Test
	void detectsSnapshotDifferentFromLedger() {
		Long id = openWithBalance("거래후잔액조작", 10_000);
		Long lastTx = jdbcTemplate.queryForObject(
				"SELECT MAX(id) FROM ledger_transaction WHERE account_id = ?", Long.class, id);
		LedgerTampering.withoutAppendOnlyTriggers(owner,
				"UPDATE ledger_transaction SET balance_after = balance_after + 5 WHERE id = ?", lastTx);
		try {
			DailyClosing closing = closeToday();

			assertThat(mismatches(closing)).filteredOn(m -> id.equals(m.accountId()))
					.singleElement().satisfies(m -> {
						assertThat(m.type()).isEqualTo(MismatchType.SNAPSHOT_MISMATCH);
						assertThat(m.expected()).isEqualByComparingTo("10000");
						assertThat(m.actual()).isEqualByComparingTo("10005");
					});
		} finally {
			LedgerTampering.withoutAppendOnlyTriggers(owner,
					"UPDATE ledger_transaction SET balance_after = balance_after - 5 WHERE id = ?", lastTx);
		}
	}

	/** 입금 분개 중 현금 쪽만 1원 바꾸면 그 거래의 차대 불일치를 찾는다. 고객 쪽 분개는 그대로라 계좌는 맞다. */
	@Test
	void detectsUnbalancedTransaction() {
		Long id = accountService.open("분개조작").getId();
		Long txId = accountService.deposit(id, 10_000, newKey()).value().getId();
		LedgerTampering.withoutAppendOnlyTriggers(owner,
				"UPDATE ledger_entry SET amount = amount + 1 WHERE transaction_id = ? AND ledger_account = 'CASH'", txId);
		try {
			DailyClosing closing = closeToday();

			assertThat(mismatches(closing)).filteredOn(m -> txId.equals(m.transactionId()))
					.singleElement().satisfies(m -> {
						assertThat(m.type()).isEqualTo(MismatchType.UNBALANCED_TRANSACTION);
						assertThat(m.expected()).isEqualByComparingTo("10001"); // 차변(현금)
						assertThat(m.actual()).isEqualByComparingTo("10000");   // 대변(고객 예금)
					});
			assertThat(mismatches(closing)).noneMatch(m -> id.equals(m.accountId()) && m.transactionId() == null);
		} finally {
			LedgerTampering.withoutAppendOnlyTriggers(owner,
					"UPDATE ledger_entry SET amount = amount - 1 WHERE transaction_id = ? AND ledger_account = 'CASH'", txId);
		}
	}

	/** 원장 거래 없이 이체 행만 있으면 반쪽 이체를 찾는다. */
	@Test
	void detectsIncompleteTransfer() {
		Long a = openWithBalance("반쪽이체A", 0);
		Long b = openWithBalance("반쪽이체B", 0);
		Long transferId = jdbcTemplate.queryForObject("SELECT transfer_seq.NEXTVAL FROM dual", Long.class);
		jdbcTemplate.update("""
				INSERT INTO transfer (id, from_account_id, to_account_id, amount, created_at)
				VALUES (?, ?, ?, 100, ?)
				""", transferId, a, b, Timestamp.valueOf(LocalDateTime.now()));
		try {
			DailyClosing closing = closeToday();

			assertThat(mismatches(closing)).filteredOn(m -> transferId.equals(m.transferId()))
					.singleElement().satisfies(m -> {
						assertThat(m.type()).isEqualTo(MismatchType.INCOMPLETE_TRANSFER);
						assertThat(m.expected()).isEqualByComparingTo("2");
						assertThat(m.actual()).isEqualByComparingTo("0");
					});
		} finally {
			LedgerTampering.withoutAppendOnlyTriggers(owner, "DELETE FROM transfer WHERE id = ?", transferId);
		}
	}

	/** 이체 가계정 분개를 바꾸면 가계정 잔액이 0이 아니게 되고, 그 거래의 차대 불일치도 함께 찾는다. */
	@Test
	void detectsClearingNotZero() {
		Long a = openWithBalance("가계정A", 10_000);
		Long b = openWithBalance("가계정B", 0);
		Long transferId = transferService.transfer(a, b, 3_000, newKey()).value().transfer().getId();
		Long outTx = jdbcTemplate.queryForObject(
				"SELECT id FROM ledger_transaction WHERE transfer_id = ? AND transaction_type = 'TRANSFER_OUT'",
				Long.class, transferId);
		LedgerTampering.withoutAppendOnlyTriggers(owner, """
				UPDATE ledger_entry SET amount = amount + 1
				WHERE transaction_id = ? AND ledger_account = 'TRANSFER_CLEARING'
				""", outTx);
		try {
			DailyClosing closing = closeToday();

			assertThat(mismatches(closing)).extracting(ClosingMismatch::type)
					.contains(MismatchType.CLEARING_NOT_ZERO);
			assertThat(mismatches(closing)).filteredOn(m -> outTx.equals(m.transactionId()))
					.extracting(ClosingMismatch::type).containsExactly(MismatchType.UNBALANCED_TRANSACTION);
		} finally {
			LedgerTampering.withoutAppendOnlyTriggers(owner, """
					UPDATE ledger_entry SET amount = amount - 1
					WHERE transaction_id = ? AND ledger_account = 'TRANSFER_CLEARING'
					""", outTx);
		}
	}

	// ───────────────────────── 실행 규칙 ─────────────────────────

	/** 이 테스트의 오늘(시계 기준)과 미래는 아직 끝나지 않아 거부한다. 실행 기록도 남지 않는다. */
	@Test
	void rejectsDateNotEnded() {
		long before = closingService.count();

		assertThatThrownBy(() -> closingService.run(TODAY.plusDays(1), ClosingTrigger.MANUAL, null))
				.isInstanceOf(BusinessDateNotEndedException.class);
		assertThatThrownBy(() -> closingService.run(TODAY.plusDays(2), ClosingTrigger.MANUAL, null))
				.isInstanceOf(BusinessDateNotEndedException.class);
		assertThat(closingService.count()).isEqualTo(before);
	}

	/** 같은 날짜가 실행 중이면 거부한다. DB 의 RUNNING 유니크 인덱스가 막는다. */
	@Test
	void rejectsWhileSameDateIsRunning() {
		LocalDate date = TODAY.minusDays(30);
		jdbcTemplate.update("""
				INSERT INTO daily_closing (id, business_date, status, trigger_type, started_at)
				VALUES (daily_closing_seq.NEXTVAL, ?, 'RUNNING', 'MANUAL', ?)
				""", java.sql.Date.valueOf(date), Timestamp.valueOf(LocalDateTime.now().plusDays(1)));
		try {
			assertThatThrownBy(() -> closingService.run(date, ClosingTrigger.MANUAL, null))
					.isInstanceOf(ClosingAlreadyRunningException.class);
		} finally {
			jdbcTemplate.update("""
					UPDATE daily_closing SET status = 'FAILED', finished_at = SYSTIMESTAMP
					WHERE business_date = ? AND status = 'RUNNING'
					""", java.sql.Date.valueOf(date));
		}
	}

	/** 서버가 꺼져 RUNNING 으로 남은 오래된 실행은 FAILED 로 정리되고, 같은 날짜를 다시 마감할 수 있다. */
	@Test
	void staleRunningClosingIsFailedAndDateCanBeClosedAgain() {
		LocalDate date = TODAY.minusDays(40);
		Long staleId = jdbcTemplate.queryForObject("SELECT daily_closing_seq.NEXTVAL FROM dual", Long.class);
		// 시계가 하루 뒤라, 지금 시각은 이 테스트 기준으로 하루(> 1시간) 전이다.
		jdbcTemplate.update("""
				INSERT INTO daily_closing (id, business_date, status, trigger_type, started_at)
				VALUES (?, ?, 'RUNNING', 'SCHEDULED', ?)
				""", staleId, java.sql.Date.valueOf(date), Timestamp.valueOf(LocalDateTime.now()));

		DailyClosing closing = closingService.run(date, ClosingTrigger.MANUAL, null);

		assertThat(closing.status()).isEqualTo(ClosingStatus.BALANCED);
		DailyClosing stale = closingService.get(staleId);
		assertThat(stale.status()).isEqualTo(ClosingStatus.FAILED);
		assertThat(stale.errorMessage()).contains("중단");
	}

	// ───────────────────────── 관리자 API ─────────────────────────

	private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
		return jwt().jwt(j -> j.subject("test-admin")).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"),
				new SimpleGrantedAuthority("ROLE_OPERATOR"));
	}

	/** 수동 마감: 201, Location, 실행한 관리자, 일계표가 담긴다. 목록과 상세로 다시 조회된다. */
	@Test
	void runAndReadThroughApi() throws Exception {
		openWithBalance("API마감", 10_000);

		String location = mockMvc.perform(post("/admin/closings").with(admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"businessDate\":\"" + TODAY + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.closing.businessDate").value(TODAY.toString()))
				.andExpect(jsonPath("$.closing.status").value("BALANCED"))
				.andExpect(jsonPath("$.closing.trigger").value("MANUAL"))
				.andExpect(jsonPath("$.closing.requestedBy").value("test-admin"))
				.andExpect(jsonPath("$.closing.depositLiability").isString())
				.andExpect(jsonPath("$.totals[?(@.type == 'DEPOSIT')].count").exists())
				.andExpect(jsonPath("$.mismatches").isEmpty())
				.andReturn().getResponse().getHeader("Location");

		mockMvc.perform(get(location).with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.closing.status").value("BALANCED"));
		mockMvc.perform(get("/admin/closings").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].businessDate").exists())
				.andExpect(jsonPath("$.totalElements").isNumber());
	}

	@Test
	void apiRejectsInvalidRequests() throws Exception {
		mockMvc.perform(post("/admin/closings").with(admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"businessDate\":\"" + TODAY.plusDays(1) + "\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("BUSINESS_DATE_NOT_ENDED"));
		mockMvc.perform(post("/admin/closings").with(admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mockMvc.perform(post("/admin/closings").with(admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"businessDate\":\"2026-13-01\"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/admin/closings/999999999").with(admin()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLOSING_NOT_FOUND"));
	}

	/** 관리자 토큰이 없으면 마감을 실행할 수 없다. */
	@Test
	void apiRequiresAdmin() throws Exception {
		long before = closingService.count();

		mockMvc.perform(post("/admin/closings")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"businessDate\":\"" + TODAY + "\"}"))
				.andExpect(status().isUnauthorized());

		assertThat(closingService.count()).isEqualTo(before);
	}

}
