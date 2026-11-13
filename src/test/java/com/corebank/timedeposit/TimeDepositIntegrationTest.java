package com.corebank.timedeposit;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.Account;
import com.corebank.account.AccountRepository;
import com.corebank.account.AccountService;
import com.corebank.account.AccountStatus;
import com.corebank.admin.AdminRole;
import com.corebank.auth.AuthSessionService;
import com.corebank.correction.CorrectionService;
import com.corebank.correction.NotCorrectableException;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.interest.InterestCalculator;
import com.corebank.interest.InterestService;
import com.corebank.product.DepositProduct;
import com.corebank.product.ProductService;
import com.corebank.product.ProductTypeMismatchException;
import com.corebank.product.TimeDepositProduct;
import com.corebank.support.MutableClock;
import com.jayway.jsonpath.JsonPath;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정기예금 통합 테스트. 앱 시계(MutableClock)를 옮겨 "가입 → 며칠 뒤 해지", "만기일" 을 만든다.
 *
 * <p>금리 3.65% 면 100만 원의 하루 이자가 정확히 100원이다. 만기 처리는 DB 의 모든 정기예금이 대상이므로
 * 검증은 이 테스트가 만든 예금만 본다.
 */
@Import({TestcontainersConfiguration.class, TimeDepositIntegrationTest.Clocks.class})
@SpringBootTest
@AutoConfigureMockMvc
class TimeDepositIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class Clocks {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}
	}

	private static final long PRINCIPAL = 1_000_000;

	@Autowired
	private MutableClock clock;

	@Autowired
	private TimeDepositService service;

	@Autowired
	private TimeDepositRepository repository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private ProductService productService;

	@Autowired
	private InterestService interestService;

	@Autowired
	private CorrectionService correctionService;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AuthSessionService tokenService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private MockMvc mockMvc;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	private static String uniqueName(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private DepositProduct timeProduct(String rate, int months) {
		return productService.createTimeDeposit(uniqueName("정기"), new BigDecimal(rate), months);
	}

	private Long demandAccount(long balance) {
		Long id = accountService.open("정기예금테스트").getId();
		if (balance > 0) {
			accountService.deposit(id, balance, newKey());
		}
		return id;
	}

	private TimeDeposit open(DepositProduct product, Long payoutId) {
		return service.open(product.getId(), PRINCIPAL, payoutId, newKey()).value();
	}

	private long balance(Long accountId) {
		return accountService.get(accountId).getBalance();
	}

	/** 원장의 고객 예금 분개 합계. 계좌 잔액과 같아야 한다 */
	private long ledgerTotal(Long accountId) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry WHERE account_id = ? AND ledger_account = 'CUSTOMER_DEPOSIT'
				""", Long.class, accountId);
	}

	private List<String> transactionTypes(Long accountId) {
		return jdbc.queryForList("SELECT transaction_type FROM ledger_transaction WHERE account_id = ? ORDER BY id",
				String.class, accountId);
	}

	private String token(AdminRole role) {
		return "Bearer " + tokenService.issueAdminToken("test-" + role.name().toLowerCase(), role).value();
	}

	private String customerToken() throws Exception {
		String body = mockMvc.perform(post("/auth/customer/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"password\":\"password-1234\",\"name\":\"정기고객\"}"
								.formatted("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	// ───────────────────────── 가입 ─────────────────────────

	/** 연결계좌에서 원금이 빠져 새 정기예금 계좌로 들어가고, 약정이율·만기일이 정해진다 */
	@Test
	void openMovesPrincipalFromPayoutAccount() {
		DepositProduct product = timeProduct("3.65", 12);
		Long payoutId = demandAccount(5_000_000);

		TimeDeposit deposit = open(product, payoutId);

		Long id = deposit.getAccountId();
		assertThat(balance(payoutId)).isEqualTo(4_000_000);
		assertThat(balance(id)).isEqualTo(PRINCIPAL);
		assertThat(ledgerTotal(id)).isEqualTo(PRINCIPAL);
		assertThat(ledgerTotal(payoutId)).isEqualTo(4_000_000);
		assertThat(transactionTypes(id)).containsExactly("TRANSFER_IN");
		assertThat(transactionTypes(payoutId)).containsExactly("DEPOSIT", "TRANSFER_OUT");

		TimeDeposit saved = service.get(id);
		LocalDate today = LocalDate.now(clock);
		assertThat(saved.getStatus()).isEqualTo(TimeDepositStatus.ACTIVE);
		assertThat(saved.getAnnualRate()).isEqualByComparingTo("3.65");
		assertThat(saved.getStartDate()).isEqualTo(today);
		assertThat(saved.getMaturityDate()).isEqualTo(today.plusMonths(12));
		Account account = accountService.get(id);
		assertThat(account.getOwnerName()).isEqualTo("정기예금테스트");
		assertThat(account.getCustomerId()).isNull();
	}

	/** 상품 금리를 바꿔도 이미 든 예금의 약정이율은 그대로고, 새로 드는 예금에만 새 금리가 붙는다(고정 금리) */
	@Test
	void contractRateIsFixedWhenProductRateChanges() {
		DepositProduct product = timeProduct("3.65", 12);
		Long payoutId = demandAccount(5_000_000);
		TimeDeposit before = open(product, payoutId);

		productService.changeRate(product.getId(), new BigDecimal("5.00"), "test-operator");
		TimeDeposit after = open(product, payoutId);

		assertThat(service.get(before.getAccountId()).getAnnualRate()).isEqualByComparingTo("3.65");
		assertThat(service.get(after.getAccountId()).getAnnualRate()).isEqualByComparingTo("5.00");
		assertThat(productService.rateHistory(product.getId()))
				// Oracle NUMBER 는 끝자리 0 을 보관하지 않아 5.00 이 5 로 읽힌다. 값으로 비교한다.
				.extracting(rate -> rate.getAnnualRate().stripTrailingZeros().toPlainString())
				.containsExactly("5", "3.65");
	}

	@Test
	void sameKeyOpensOnlyOnce() {
		DepositProduct product = timeProduct("3.65", 12);
		Long payoutId = demandAccount(5_000_000);
		IdempotencyKey key = newKey();

		Long first = service.open(product.getId(), PRINCIPAL, payoutId, key).value().getAccountId();
		var replay = service.open(product.getId(), PRINCIPAL, payoutId, key);

		assertThat(replay.replayed()).isTrue();
		assertThat(replay.value().getAccountId()).isEqualTo(first);
		assertThat(balance(payoutId)).isEqualTo(4_000_000);
	}

	@Test
	void openRejectsWrongProductTypeAndShortBalance() throws Exception {
		DepositProduct demand = productService.create(uniqueName("입출금"), new BigDecimal("1.00"));
		DepositProduct time = timeProduct("3.65", 12);
		Long payoutId = demandAccount(500_000);

		assertThatThrownBy(() -> open(demand, payoutId)).isInstanceOf(ProductTypeMismatchException.class);

		// 잔액 부족이면 아무것도 남지 않는다(새 계좌도 롤백).
		long accountsBefore = accountRepository.count();
		mockMvc.perform(post("/time-deposits")
						.header(HttpHeaders.AUTHORIZATION, token(AdminRole.TELLER))
						.header(IdempotencyKey.HEADER, newKey().value())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"productId\":%d,\"principal\":%d,\"payoutAccountId\":%d}"
								.formatted(time.getId(), PRINCIPAL, payoutId)))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
		assertThat(accountRepository.count()).isEqualTo(accountsBefore);
		assertThat(balance(payoutId)).isEqualTo(500_000);

		// 정기예금 상품으로 입출금 계좌를 열 수 없다.
		mockMvc.perform(post("/accounts")
						.header(HttpHeaders.AUTHORIZATION, token(AdminRole.TELLER))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ownerName\":\"홍길동\",\"productId\":%d}".formatted(time.getId())))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("PRODUCT_TYPE_MISMATCH"));
	}

	/** 정기예금을 연결계좌로 쓸 수 없다(원금이 정기예금끼리 옮겨 다니면 약정이 꼬인다) */
	@Test
	void payoutAccountMustBeDemandAccount() {
		DepositProduct product = timeProduct("3.65", 12);
		TimeDeposit deposit = open(product, demandAccount(5_000_000));

		assertThatThrownBy(() -> open(product, deposit.getAccountId()))
				.isInstanceOf(com.corebank.account.TimeDepositRestrictedException.class);
	}

	// ───────────────────────── 고객 ─────────────────────────

	/** 고객은 본인 계좌를 연결계좌로 가입하고, 내 계좌 목록에 정기예금이 함께 보인다. 남의 계좌로는 가입할 수 없다(404) */
	@Test
	void customerOpensWithOwnAccountOnly() throws Exception {
		DepositProduct product = timeProduct("3.65", 6);
		String customer = customerToken();
		String body = mockMvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, customer)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long payoutId = ((Number) JsonPath.read(body, "$.id")).longValue();
		accountService.deposit(payoutId, 3_000_000, newKey());

		String opened = mockMvc.perform(post("/time-deposits")
						.header(HttpHeaders.AUTHORIZATION, customer)
						.header(IdempotencyKey.HEADER, newKey().value())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"productId\":%d,\"principal\":%d,\"payoutAccountId\":%d}"
								.formatted(product.getId(), PRINCIPAL, payoutId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.principal").value(PRINCIPAL))
				.andExpect(jsonPath("$.ownerName").value("정기고객"))
				.andExpect(jsonPath("$.annualRate").value(3.65))
				.andExpect(jsonPath("$.quote.trigger").value("EARLY_TERM"))
				.andExpect(jsonPath("$.quote.payout").value(PRINCIPAL))
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(opened, "$.accountId")).longValue();

		mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, customer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts[0].id").value(id))
				.andExpect(jsonPath("$.accounts[0].product.type").value("TIME"))
				.andExpect(jsonPath("$.accounts[1].product.type").value("DEMAND"));

		// 남의 계좌를 연결계좌로
		Long others = demandAccount(5_000_000);
		mockMvc.perform(post("/time-deposits")
						.header(HttpHeaders.AUTHORIZATION, customer)
						.header(IdempotencyKey.HEADER, newKey().value())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"productId\":%d,\"principal\":%d,\"payoutAccountId\":%d}"
								.formatted(product.getId(), PRINCIPAL, others)))
				.andExpect(status().isNotFound());
		assertThat(balance(others)).isEqualTo(5_000_000);

		// 남의 정기예금 조회
		mockMvc.perform(get("/accounts/{id}/time-deposit", id).header(HttpHeaders.AUTHORIZATION, customerToken()))
				.andExpect(status().isNotFound());
	}

	// ───────────────────────── 입출금 제한 ─────────────────────────

	/** 정기예금 계좌에는 입금·출금·이체·계좌 해지를 할 수 없고, 가입 중인 예금의 연결계좌는 해지할 수 없다 */
	@Test
	void demandTransactionsAreRejected() throws Exception {
		Long payoutId = demandAccount(5_000_000);
		Long id = open(timeProduct("3.65", 12), payoutId).getAccountId();
		String teller = token(AdminRole.TELLER);

		for (String path : List.of("/accounts/{id}/deposits", "/accounts/{id}/withdrawals")) {
			mockMvc.perform(post(path, id)
							.header(HttpHeaders.AUTHORIZATION, teller)
							.header(IdempotencyKey.HEADER, newKey().value())
							.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("TIME_DEPOSIT_RESTRICTED"));
		}
		for (String transfer : List.of("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":1000}".formatted(id, payoutId),
				"{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":1000}".formatted(payoutId, id))) {
			mockMvc.perform(post("/transfers")
							.header(HttpHeaders.AUTHORIZATION, teller)
							.header(IdempotencyKey.HEADER, newKey().value())
							.contentType(MediaType.APPLICATION_JSON).content(transfer))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("TIME_DEPOSIT_RESTRICTED"));
		}
		mockMvc.perform(post("/accounts/{id}/closure", id)
						.header(HttpHeaders.AUTHORIZATION, teller)
						.header(IdempotencyKey.HEADER, newKey().value()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TIME_DEPOSIT_RESTRICTED"));
		mockMvc.perform(post("/accounts/{id}/closure", payoutId)
						.header(HttpHeaders.AUTHORIZATION, teller)
						.header(IdempotencyKey.HEADER, newKey().value()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("LINKED_TIME_DEPOSIT_EXISTS"));

		assertThat(balance(id)).isEqualTo(PRINCIPAL);
		assertThat(balance(payoutId)).isEqualTo(4_000_000);
	}

	/** 가입 원금 이체는 정정(역분개)할 수 없다. 되돌리면 계약 원금과 잔액이 어긋난다 */
	@Test
	void fundingTransferIsNotCorrectable() {
		Long payoutId = demandAccount(5_000_000);
		Long id = open(timeProduct("3.65", 12), payoutId).getAccountId();
		Long transferIn = jdbc.queryForObject(
				"SELECT id FROM ledger_transaction WHERE account_id = ? AND transaction_type = 'TRANSFER_IN'", Long.class, id);

		assertThatThrownBy(() -> correctionService.request(transferIn, "잘못 가입", "test-teller"))
				.isInstanceOf(NotCorrectableException.class);
	}

	// ───────────────────────── 중도해지 ─────────────────────────

	/**
	 * 가입 45일 뒤 중도해지: 12개월 3.65% 상품의 1~3개월 구간 이율은 3.65 × 30% = 1.095 → 1.09%.
	 * 세전 1,000,000 × 1.09 × 45 / 36,500 = 1,343. 소득세 188 → 180, 지방세 18 → 10. 세후 1,153.
	 */
	@Test
	void earlyTerminationPaysReducedInterestToPayoutAccount() throws Exception {
		Long payoutId = demandAccount(5_000_000);
		Long id = open(timeProduct("3.65", 12), payoutId).getAccountId();
		clock.advanceDays(45);

		mockMvc.perform(post("/accounts/{id}/time-deposit/termination", id)
						.header(HttpHeaders.AUTHORIZATION, token(AdminRole.TELLER))
						.header(IdempotencyKey.HEADER, "term-" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("TERMINATED"))
				.andExpect(jsonPath("$.accountStatus").value("CLOSED"))
				.andExpect(jsonPath("$.balance").value(0))
				.andExpect(jsonPath("$.quote").doesNotExist())
				.andExpect(jsonPath("$.settlement.appliedRate").value(1.09))
				.andExpect(jsonPath("$.settlement.grossInterest").value(1_343))
				.andExpect(jsonPath("$.settlement.tax").value(190))
				.andExpect(jsonPath("$.settlement.netInterest").value(1_153))
				.andExpect(jsonPath("$.settlement.payout").value(1_001_153));

		assertThat(balance(payoutId)).isEqualTo(4_000_000 + 1_001_153);
		assertThat(balance(id)).isZero();
		assertThat(ledgerTotal(id)).isZero();
		assertThat(ledgerTotal(payoutId)).isEqualTo(balance(payoutId));
		assertThat(transactionTypes(id)).containsExactly("TRANSFER_IN", "INTEREST", "TRANSFER_OUT");
		Map<String, Object> payment = jdbc.queryForMap(
				"SELECT trigger_type, day_count, gross_amount, net_amount FROM interest_payment WHERE account_id = ?", id);
		assertThat(payment).containsEntry("TRIGGER_TYPE", "EARLY_TERM");
		assertThat(((Number) payment.get("DAY_COUNT")).intValue()).isEqualTo(45);
		assertThat(((Number) payment.get("NET_AMOUNT")).longValue()).isEqualTo(1_153);

		// 같은 키로 다시: 처음 결과(재전송 표시), 돈은 한 번만
		mockMvc.perform(post("/accounts/{id}/time-deposit/termination", id)
						.header(HttpHeaders.AUTHORIZATION, token(AdminRole.TELLER))
						.header(IdempotencyKey.HEADER, "term-" + id))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.settlement.payout").value(1_001_153));
		// 다른 키로 다시: 이미 해지
		mockMvc.perform(post("/accounts/{id}/time-deposit/termination", id)
						.header(HttpHeaders.AUTHORIZATION, token(AdminRole.TELLER))
						.header(IdempotencyKey.HEADER, newKey().value()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
		assertThat(balance(payoutId)).isEqualTo(4_000_000 + 1_001_153);

		// 해지가 끝났으니 연결계좌는 해지할 수 있다.
		accountService.close(payoutId, newKey());
		assertThat(accountService.get(payoutId).getStatus()).isEqualTo(AccountStatus.CLOSED);
	}

	/** 가입 당일 해지: 이자 붙은 날이 0일이라 이자 지급 기록 없이 원금만 돌아간다 */
	@Test
	void sameDayTerminationReturnsPrincipalOnly() {
		Long payoutId = demandAccount(5_000_000);
		Long id = open(timeProduct("3.65", 12), payoutId).getAccountId();

		TimeDepositService.Settled settled = service.terminate(id, newKey()).value();

		assertThat(settled.payment()).isNull();
		assertThat(settled.payout()).isEqualTo(PRINCIPAL);
		assertThat(service.get(id).getInterestPaymentId()).isNull();
		assertThat(service.get(id).getAppliedRate()).isEqualByComparingTo("0.10");
		assertThat(balance(payoutId)).isEqualTo(5_000_000);
		assertThat(transactionTypes(id)).containsExactly("TRANSFER_IN", "TRANSFER_OUT");
	}

	// ───────────────────────── 만기 ─────────────────────────

	/** 만기일이 되면 만기 처리가 약정이율로 이자를 주고 원리금을 연결계좌로 보낸다. 다시 실행해도 두 번 주지 않는다 */
	@Test
	void maturityPaysContractInterestOnce() throws Exception {
		Long payoutId = demandAccount(5_000_000);
		TimeDeposit deposit = open(timeProduct("3.65", 1), payoutId);
		Long id = deposit.getAccountId();
		int days = (int) ChronoUnit.DAYS.between(deposit.getStartDate(), deposit.getMaturityDate());
		InterestCalculator.Interest expected = InterestCalculator.of(BigDecimal.valueOf(100L * days * 36_500));

		// 만기 전날에는 대상이 아니다.
		clock.moveTo(deposit.getMaturityDate().minusDays(1));
		service.matureDue();
		assertThat(service.get(id).getStatus()).isEqualTo(TimeDepositStatus.ACTIVE);

		clock.moveTo(deposit.getMaturityDate());
		mockMvc.perform(post("/admin/time-deposits/maturities").header(HttpHeaders.AUTHORIZATION, token(AdminRole.OPERATOR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.businessDate").value(deposit.getMaturityDate().toString()))
				.andExpect(jsonPath("$.failed").value(0));

		TimeDeposit matured = service.get(id);
		assertThat(matured.getStatus()).isEqualTo(TimeDepositStatus.MATURED);
		assertThat(matured.getAppliedRate()).isEqualByComparingTo("3.65");
		assertThat(expected.gross()).isEqualTo(100L * days);
		long payout = PRINCIPAL + expected.net();
		assertThat(balance(payoutId)).isEqualTo(4_000_000 + payout);
		assertThat(accountService.get(id).getStatus()).isEqualTo(AccountStatus.CLOSED);
		assertThat(jdbc.queryForObject("SELECT trigger_type FROM interest_payment WHERE account_id = ?", String.class, id))
				.isEqualTo("MATURITY");

		service.matureDue();
		assertThat(balance(payoutId)).isEqualTo(4_000_000 + payout);

		// 창구는 만기 처리를 실행할 수 없다(운영 역할).
		mockMvc.perform(post("/admin/time-deposits/maturities").header(HttpHeaders.AUTHORIZATION, token(AdminRole.TELLER)))
				.andExpect(status().isForbidden());
	}

	/** 만기 처리가 돌기 전에 고객이 해지하면 중도해지가 아니라 만기 해지다 */
	@Test
	void terminationAfterMaturityDateIsMaturity() {
		Long payoutId = demandAccount(5_000_000);
		TimeDeposit deposit = open(timeProduct("3.65", 1), payoutId);
		clock.moveTo(deposit.getMaturityDate().plusDays(2));

		service.terminate(deposit.getAccountId(), newKey());

		TimeDeposit settled = service.get(deposit.getAccountId());
		assertThat(settled.getStatus()).isEqualTo(TimeDepositStatus.MATURED);
		assertThat(settled.getAppliedRate()).isEqualByComparingTo("3.65");
	}

	@Test
	void adminListsTimeDeposits() throws Exception {
		Long id = open(timeProduct("3.65", 12), demandAccount(5_000_000)).getAccountId();

		mockMvc.perform(get("/admin/time-deposits").param("status", "ACTIVE")
						.header(HttpHeaders.AUTHORIZATION, token(AdminRole.TELLER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].accountId").value(id))
				.andExpect(jsonPath("$.content[0].productName").exists())
				.andExpect(jsonPath("$.content[0].atMaturity.trigger").value("MATURITY"));
	}

	// ───────────────────────── 이자 적립·상품 ─────────────────────────

	/** 정기예금은 매일 적수를 쌓지 않는다. 입출금 계좌는 금리 이력에서 그날의 금리를 쓴다 */
	@Test
	void accrualSkipsTimeDepositsAndUsesRateOfThatDay() {
		LocalDate today = LocalDate.now(clock);
		DepositProduct demand = productService.create(uniqueName("변동"), new BigDecimal("3.65"));
		Long demandId = accountService.open("변동금리", demand.getId()).getId();
		accountService.deposit(demandId, PRINCIPAL, newKey());
		Long timeId = open(timeProduct("3.65", 12), demandAccount(5_000_000)).getAccountId();
		// 내일부터 7.30%. 실제로 내일 바꾸는 것과 같은 이력 행을 넣는다(서비스는 늘 "오늘부터" 라 내일 날짜를 만들 수 없다).
		jdbc.update("""
				INSERT INTO deposit_product_rate (id, product_id, annual_rate, effective_from, changed_by, created_at)
				VALUES (deposit_product_rate_seq.NEXTVAL, ?, 7.30, ?, 'test', LOCALTIMESTAMP)
				""", demand.getId(), java.sql.Date.valueOf(today.plusDays(1)));

		clock.advanceDays(3);
		interestService.accrue(today);
		interestService.accrue(today.plusDays(1));

		assertThat(jdbc.queryForList(
				"SELECT TO_CHAR(annual_rate, 'FM0.00') FROM interest_accrual WHERE account_id = ? ORDER BY accrual_date",
				String.class, demandId)).containsExactly("3.65", "7.30");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interest_accrual WHERE account_id = ?", Integer.class, timeId))
				.isZero();
	}

	/** 오늘 바꾼 금리는 오늘 마감 잔액부터 붙는다 */
	@Test
	void rateChangedTodayAppliesFromToday() {
		LocalDate today = LocalDate.now(clock);
		DepositProduct demand = productService.create(uniqueName("변동"), new BigDecimal("3.65"));
		Long demandId = accountService.open("변동금리", demand.getId()).getId();
		accountService.deposit(demandId, PRINCIPAL, newKey());

		productService.changeRate(demand.getId(), new BigDecimal("1.50"), "test-operator");
		clock.advanceDays(2);
		interestService.accrue(today);

		assertThat(jdbc.queryForObject("SELECT annual_rate FROM interest_accrual WHERE account_id = ?", BigDecimal.class,
				demandId)).isEqualByComparingTo("1.50");
		assertThat(productService.get(demand.getId()).getAnnualRate()).isEqualByComparingTo("1.50");
	}

	/**
	 * 지연 로딩 프록시와 상속: 계좌의 상품은 처음에 DepositProduct 를 상속한 프록시라 instanceof TimeDepositProduct 가 false 다.
	 * 메서드 호출(isTimeDeposit)은 실제 객체로 넘어가 맞는 답이 나오고, Hibernate.unproxy 로 실제 객체를 꺼낼 수 있다.
	 */
	@Test
	void lazyProductProxyIsNotInstanceOfSubclass() {
		Long id = open(timeProduct("3.65", 12), demandAccount(5_000_000)).getAccountId();

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			DepositProduct product = accountRepository.findById(id).orElseThrow().getProduct();

			assertThat(Hibernate.isInitialized(product)).isFalse();
			assertThat(product instanceof TimeDepositProduct).isFalse();
			assertThat(product.isTimeDeposit()).isTrue();
			assertThat(Hibernate.unproxy(product)).isInstanceOf(TimeDepositProduct.class);
		});
	}

	@Test
	void productApiCreatesTimeDepositAndChangesRate() throws Exception {
		String operator = token(AdminRole.OPERATOR);
		mockMvc.perform(post("/admin/products").header(HttpHeaders.AUTHORIZATION, operator)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"%s\",\"type\":\"TIME\",\"annualRate\":3.00}".formatted(uniqueName("기간없음"))))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/admin/products").header(HttpHeaders.AUTHORIZATION, operator)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"%s\",\"annualRate\":3.00,\"termMonths\":12}".formatted(uniqueName("입출금기간"))))
				.andExpect(status().isBadRequest());

		String created = mockMvc.perform(post("/admin/products").header(HttpHeaders.AUTHORIZATION, operator)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"%s\",\"type\":\"TIME\",\"annualRate\":3.00,\"termMonths\":12}"
								.formatted(uniqueName("정기"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value("TIME"))
				.andExpect(jsonPath("$.termMonths").value(12))
				.andReturn().getResponse().getContentAsString();
		long productId = ((Number) JsonPath.read(created, "$.id")).longValue();

		mockMvc.perform(post("/admin/products/{id}/rate-changes", productId).header(HttpHeaders.AUTHORIZATION, operator)
						.contentType(MediaType.APPLICATION_JSON).content("{\"annualRate\":3.25}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.annualRate").value(3.25));
		mockMvc.perform(get("/admin/products/{id}/rates", productId).header(HttpHeaders.AUTHORIZATION, operator))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].annualRate").value(3.25))
				.andExpect(jsonPath("$.content[0].changedBy").value("test-operator"))
				.andExpect(jsonPath("$.content[1].changedBy").doesNotExist());

		mockMvc.perform(post("/admin/products/{id}/rate-changes", productId)
						.header(HttpHeaders.AUTHORIZATION, token(AdminRole.TELLER))
						.contentType(MediaType.APPLICATION_JSON).content("{\"annualRate\":9.00}"))
				.andExpect(status().isForbidden());
	}

}
