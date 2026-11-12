package com.corebank.interest;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.AccountService;
import com.corebank.closing.BusinessDateNotEndedException;
import com.corebank.closing.ClosingService;
import com.corebank.closing.ClosingTrigger;
import com.corebank.closing.DailyClosing;
import com.corebank.correction.CorrectionService;
import com.corebank.correction.NotCorrectableException;
import com.corebank.auth.JwtTokenService;
import com.corebank.product.DepositProduct;
import com.corebank.product.ProductNotAvailableException;
import com.corebank.product.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이자 통합 테스트. 앱 시계를 40일 뒤로 옮겨 오늘·내일을 "끝난 날", 이번 달을 "끝난 달" 로 만든다
 * (거래는 실제 시각으로 기록되므로, 오늘 넣은 돈이 오늘·내일 마감 잔액이 된다).
 *
 * <p>금리 3.65% 를 쓰면 100만 원의 하루 이자가 정확히 100원이라 기대값을 손으로 확인하기 쉽다.
 * 적립·결산은 DB 의 모든 계좌를 대상으로 하므로, 검증은 이 테스트가 만든 계좌만 본다.
 */
@Import({TestcontainersConfiguration.class, InterestIntegrationTest.LaterClock.class})
@SpringBootTest
@AutoConfigureMockMvc
class InterestIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class LaterClock {

		@Bean
		@Primary
		Clock laterClock() {
			return Clock.offset(Clock.systemDefaultZone(), Duration.ofDays(40));
		}
	}

	private static final LocalDate TODAY = LocalDate.now();
	private static final LocalDate TOMORROW = TODAY.plusDays(1);

	@Autowired
	private InterestService interestService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private ProductService productService;

	@Autowired
	private CorrectionService correctionService;

	@Autowired
	private ClosingService closingService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtTokenService tokenService;

	@Autowired
	private MockMvc mockMvc;

	private DepositProduct product(String rate) {
		return productService.create("테스트상품-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal(rate));
	}

	private Long openWith(DepositProduct product, long balance) {
		Long id = accountService.open("이자테스트", product.getId()).getId();
		if (balance > 0) {
			accountService.deposit(id, balance, newKey());
		}
		return id;
	}

	private List<Long> accrualBalances(Long accountId) {
		return jdbcTemplate.queryForList(
				"SELECT balance FROM interest_accrual WHERE account_id = ? ORDER BY accrual_date", Long.class, accountId);
	}

	private long ledgerTotal(Long accountId) {
		return jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry WHERE account_id = ? AND ledger_account = 'CUSTOMER_DEPOSIT'
				""", Long.class, accountId);
	}

	/** 하루에 한 번만 쌓인다. 잔액은 그날 마감 시점의 거래 후 잔액이다. */
	@Test
	void accruesOncePerDay() {
		Long account = openWith(product("3.65"), 1_000_000);

		interestService.accrue(TODAY);
		interestService.accrue(TODAY);

		assertThat(accrualBalances(account)).containsExactly(1_000_000L);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT annual_rate FROM interest_accrual WHERE account_id = ?", BigDecimal.class, account))
				.isEqualByComparingTo("3.65");
	}

	/** 잔액 0인 계좌와 금리 0% 상품 계좌는 쌓지 않는다. 오늘(시계 기준)과 미래는 거부한다. */
	@Test
	void skipsZeroAndRejectsUnfinishedDay() {
		Long empty = openWith(product("3.65"), 0);
		Long zeroRate = openWith(product("0"), 1_000_000);

		interestService.accrue(TODAY);

		assertThat(accrualBalances(empty)).isEmpty();
		assertThat(accrualBalances(zeroRate)).isEmpty();
		LocalDate clockToday = LocalDate.now(Clock.offset(Clock.systemDefaultZone(), Duration.ofDays(40)));
		assertThatThrownBy(() -> interestService.accrue(clockToday)).isInstanceOf(BusinessDateNotEndedException.class);
		assertThatThrownBy(() -> interestService.settle(YearMonth.from(clockToday)))
				.isInstanceOf(MonthNotEndedException.class);
	}

	/**
	 * 이틀치(100원 × 2) 결산: 세전 200원, 소득세 28원 → 20원, 지방세 2원 → 0원, 세후 180원.
	 * 잔액·원장·분개·지급 기록이 모두 맞고, 다시 결산해도 두 번 지급하지 않는다.
	 */
	@Test
	void settlementPaysInterestOnceWithTax() {
		Long account = openWith(product("3.65"), 1_000_000);
		interestService.accrue(TODAY);
		interestService.accrue(TOMORROW);

		interestService.settle(YearMonth.from(TOMORROW));
		interestService.settle(YearMonth.from(TOMORROW));

		assertThat(accountService.get(account).getBalance()).isEqualTo(1_000_180);
		assertThat(ledgerTotal(account)).isEqualTo(1_000_180);
		var payment = jdbcTemplate.queryForMap("""
				SELECT trigger_type, day_count, gross_amount, income_tax, local_tax, net_amount, ledger_transaction_id
				FROM interest_payment WHERE account_id = ?
				""", account);
		assertThat(payment.get("TRIGGER_TYPE")).isEqualTo("SETTLEMENT");
		assertThat(((Number) payment.get("DAY_COUNT")).intValue()).isEqualTo(2);
		assertThat(((Number) payment.get("GROSS_AMOUNT")).longValue()).isEqualTo(200);
		assertThat(((Number) payment.get("INCOME_TAX")).longValue()).isEqualTo(20);
		assertThat(((Number) payment.get("LOCAL_TAX")).longValue()).isZero();
		assertThat(((Number) payment.get("NET_AMOUNT")).longValue()).isEqualTo(180);

		long txId = ((Number) payment.get("LEDGER_TRANSACTION_ID")).longValue();
		assertThat(jdbcTemplate.queryForList("""
				SELECT ledger_account || ':' || entry_side || ':' || amount
				FROM ledger_entry WHERE transaction_id = ? ORDER BY ledger_account
				""", String.class, txId))
				.containsExactly("CUSTOMER_DEPOSIT:CREDIT:180", "INTEREST_EXPENSE:DEBIT:200", "INTEREST_TAX_PAYABLE:CREDIT:20");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM interest_accrual WHERE account_id = ? AND payment_id IS NULL", Long.class, account))
				.isZero();
		// 일 마감은 이자 거래를 정상으로 본다: 차변 200 = 대변 200, 고객 예금 분개 180 = 거래 금액 180(복합 분개)
		DailyClosing closing = closingService.run(TODAY, ClosingTrigger.MANUAL, "test-admin");
		assertThat(closingService.mismatches(closing.id())).noneMatch(m -> Long.valueOf(txId).equals(m.transactionId()));
		// 은행이 계산해 넣은 이자는 정정 대상이 아니다(지급 기록과 어긋난다).
		assertThatThrownBy(() -> correctionService.request(txId, "이자 취소", "maker"))
				.isInstanceOf(NotCorrectableException.class);
	}

	/**
	 * 결산은 계좌마다 따로 커밋한다. 한 계좌가 실패해도(잔액 한도 초과) 다른 계좌는 지급되고, 실패한 계좌의 적수는
	 * 지급되지 않은 채 남아 다음 결산에서 다시 시도된다.
	 */
	@Test
	void oneAccountFailureDoesNotStopOthers() {
		DepositProduct product = product("3.65");
		Long full = openWith(product, Long.MAX_VALUE);
		Long normal = openWith(product, 1_000_000);
		interestService.accrue(TODAY);

		InterestService.SettlementResult result = interestService.settle(YearMonth.from(TODAY));

		assertThat(result.failed()).isPositive();
		assertThat(accountService.get(full).getBalance()).isEqualTo(Long.MAX_VALUE);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM interest_accrual WHERE account_id = ? AND payment_id IS NULL", Long.class, full))
				.isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM interest_payment WHERE account_id = ?", Long.class, full)).isZero();
		assertThat(accountService.get(normal).getBalance()).isEqualTo(1_000_090);
	}

	/** 해지하면 어제(시계 기준)까지 쌓인 이자를 먼저 넣고, 이자까지 포함한 잔액을 지급한다. */
	@Test
	void closurePaysAccruedInterest() {
		Long account = openWith(product("3.65"), 1_000_000);
		interestService.accrue(TODAY);

		long payout = accountService.close(account, newKey()).value().payoutAmount();

		assertThat(payout).isEqualTo(1_000_090);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT trigger_type FROM interest_payment WHERE account_id = ?", String.class, account))
				.isEqualTo("CLOSURE");
		assertThat(ledgerTotal(account)).isZero();
	}

	/** 판매 중지 상품으로는 못 열지만, 이미 연 계좌는 계속 이자가 붙는다. 기본 상품은 중지할 수 없다. */
	@Test
	void discontinuedProductKeepsExistingAccounts() {
		DepositProduct product = product("3.65");
		Long account = openWith(product, 1_000_000);

		productService.discontinue(product.getId());

		assertThatThrownBy(() -> accountService.open("새 계좌", product.getId()))
				.isInstanceOf(ProductNotAvailableException.class);
		assertThatThrownBy(() -> productService.discontinue(product.getId()))
				.isInstanceOf(ProductNotAvailableException.class);
		assertThatThrownBy(() -> productService.discontinue(DepositProduct.DEFAULT_ID))
				.isInstanceOf(ProductNotAvailableException.class);
		interestService.accrue(TODAY);
		assertThat(accrualBalances(account)).containsExactly(1_000_000L);
	}

	/** HTTP: 계좌 이자 현황의 예상 이자, 관리자 적립·결산 API, 상품 등록 검증 */
	@Test
	void httpFlow() throws Exception {
		String admin = "Bearer " + tokenService.issueAdminToken("test-admin").value();
		Long account = openWith(product("3.65"), 1_000_000);

		mockMvc.perform(post("/admin/interest/accruals").header(HttpHeaders.AUTHORIZATION, admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"businessDate\":\"" + TODAY + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.businessDate").value(TODAY.toString()));
		mockMvc.perform(get("/accounts/" + account + "/interest").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unpaid.days").value(1))
				.andExpect(jsonPath("$.unpaid.estimatedGross").value(100))
				.andExpect(jsonPath("$.unpaid.estimatedTax").value(10))
				.andExpect(jsonPath("$.unpaid.estimatedNet").value(90))
				.andExpect(jsonPath("$.payments.length()").value(0));
		mockMvc.perform(get("/accounts/" + account).header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(jsonPath("$.product.annualRate").value(3.65));

		mockMvc.perform(post("/admin/interest/settlements").header(HttpHeaders.AUTHORIZATION, admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"month\":\"" + YearMonth.from(TODAY) + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.month").value(YearMonth.from(TODAY).toString()));
		mockMvc.perform(get("/accounts/" + account + "/interest").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(jsonPath("$.unpaid").doesNotExist())
				.andExpect(jsonPath("$.payments[0].netAmount").value(90));
		mockMvc.perform(get("/accounts/" + account + "/transactions").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(jsonPath("$.transactions[0].type").value("INTEREST"))
				.andExpect(jsonPath("$.transactions[0].direction").value("IN"));

		// 금리 셋째 자리, 20% 초과, 음수는 400
		for (String rate : List.of("1.234", "20.01", "-1")) {
			mockMvc.perform(post("/admin/products").header(HttpHeaders.AUTHORIZATION, admin)
							.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"잘못된 금리\",\"annualRate\":" + rate + "}"))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\":\"홍길동\",\"productId\":999999}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
	}

}
