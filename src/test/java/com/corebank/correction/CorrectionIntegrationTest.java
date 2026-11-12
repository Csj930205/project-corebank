package com.corebank.correction;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.AccountClosedException;
import com.corebank.account.AccountService;
import com.corebank.account.InsufficientBalanceException;
import com.corebank.auth.JwtTokenService;
import com.corebank.ledger.LedgerTransaction;
import com.corebank.ledger.TransactionType;
import com.corebank.transfer.TransferService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 거래 정정 통합 테스트. 실제 Oracle 에서 역분개, 승인 규칙(4-eyes), 동시 결정, DB 제약을 검증한다. Docker 가 필요하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CorrectionIntegrationTest {

	private static final String MAKER = "maker-admin";
	private static final String CHECKER = "checker-admin";

	@Autowired
	private CorrectionService correctionService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private TransferService transferService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtTokenService tokenService;

	@Autowired
	private MockMvc mockMvc;

	private Long openWithBalance(long balance) {
		Long id = accountService.open("정정테스트").getId();
		if (balance > 0) {
			accountService.deposit(id, balance, newKey());
		}
		return id;
	}

	private long balance(Long accountId) {
		return accountService.get(accountId).getBalance();
	}

	/** 고객 예금 원장 합계. 정정 뒤에도 계좌 잔액과 같아야 한다. */
	private long ledgerTotal(Long accountId) {
		return jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry WHERE account_id = ? AND ledger_account = 'CUSTOMER_DEPOSIT'
				""", Long.class, accountId);
	}

	private List<String> entries(Long transactionId) {
		return jdbcTemplate.queryForList("""
				SELECT ledger_account || ':' || entry_side || ':' || amount
				FROM ledger_entry WHERE transaction_id = ? ORDER BY ledger_account
				""", String.class, transactionId);
	}

	private long reversalCount(Long transactionId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE reversal_of_id = ?", Long.class, transactionId);
	}

	private CorrectionStatus statusOf(Long correctionId) {
		return correctionService.get(correctionId).status();
	}

	/** 요청만으로는 아무것도 바뀌지 않는다. 다른 관리자가 승인하면 입금이 역분개되어 잔액이 돌아온다. */
	@Test
	void approvedDepositCorrectionReversesIt() {
		Long account = openWithBalance(10_000);
		LedgerTransaction mistake = accountService.deposit(account, 90_000, newKey()).value();

		CorrectionView requested = correctionService.request(mistake.getId(), "10,000원을 100,000원으로 잘못 입력", MAKER);
		assertThat(requested.status()).isEqualTo(CorrectionStatus.REQUESTED);
		assertThat(balance(account)).isEqualTo(100_000);
		assertThat(reversalCount(mistake.getId())).isZero();

		CorrectionView approved = correctionService.approve(requested.id(), CHECKER, "고객 확인함");

		assertThat(approved.status()).isEqualTo(CorrectionStatus.APPROVED);
		assertThat(approved.decidedBy()).isEqualTo(CHECKER);
		assertThat(approved.reversalTransactionIds()).hasSize(1);
		assertThat(balance(account)).isEqualTo(10_000);
		assertThat(ledgerTotal(account)).isEqualTo(10_000);

		Long reversalId = approved.reversalTransactionIds().getFirst();
		Long reversalOf = jdbcTemplate.queryForObject(
				"SELECT reversal_of_id FROM ledger_transaction WHERE id = ?", Long.class, reversalId);
		assertThat(reversalOf).isEqualTo(mistake.getId());
		// 원래 거래는 그대로 남고(추가만), 역분개는 같은 분개의 차변·대변을 바꾼 것이다.
		assertThat(entries(mistake.getId())).containsExactly("CASH:DEBIT:90000", "CUSTOMER_DEPOSIT:CREDIT:90000");
		assertThat(entries(reversalId)).containsExactly("CASH:CREDIT:90000", "CUSTOMER_DEPOSIT:DEBIT:90000");
		assertThat(jdbcTemplate.queryForObject("SELECT transaction_type || ':' || balance_after FROM ledger_transaction WHERE id = ?",
				String.class, reversalId)).isEqualTo("REVERSAL_OUT:10000");
	}

	/** 출금을 되돌리면 잔액이 는다(REVERSAL_IN). */
	@Test
	void approvedWithdrawalCorrectionRestoresBalance() {
		Long account = openWithBalance(50_000);
		LedgerTransaction mistake = accountService.withdraw(account, 30_000, newKey()).value();

		CorrectionView view = correctionService.approve(
				correctionService.request(mistake.getId(), "출금 중복 처리", MAKER).id(), CHECKER, null);

		assertThat(balance(account)).isEqualTo(50_000);
		assertThat(ledgerTotal(account)).isEqualTo(50_000);
		assertThat(jdbcTemplate.queryForObject("SELECT transaction_type FROM ledger_transaction WHERE id = ?",
				String.class, view.reversalTransactionIds().getFirst())).isEqualTo("REVERSAL_IN");
	}

	/** 이체는 입금 쪽 거래로 요청해도 이체 전체(두 거래)를 되돌린다. 이체 가계정 합계는 계속 0이다. */
	@Test
	void transferCorrectionReversesBothLegs() {
		Long from = openWithBalance(30_000);
		Long to = openWithBalance(5_000);
		Long transferId = transferService.transfer(from, to, 20_000, newKey()).value().transfer().getId();
		List<Long> legs = jdbcTemplate.queryForList(
				"SELECT id FROM ledger_transaction WHERE transfer_id = ? ORDER BY id", Long.class, transferId);
		Long outLeg = legs.get(0);
		Long inLeg = legs.get(1);

		CorrectionView requested = correctionService.request(inLeg, "착오 송금", MAKER);
		assertThat(requested.transactionId()).isEqualTo(outLeg);
		assertThat(requested.transactionType()).isEqualTo(TransactionType.TRANSFER_OUT);
		assertThat(requested.accountId()).isEqualTo(from);
		assertThat(requested.counterpartAccountNumber()).isEqualTo(accountService.get(to).getAccountNumber());

		CorrectionView approved = correctionService.approve(requested.id(), CHECKER, null);

		assertThat(approved.reversalTransactionIds()).hasSize(2);
		assertThat(balance(from)).isEqualTo(30_000);
		assertThat(balance(to)).isEqualTo(5_000);
		assertThat(ledgerTotal(from)).isEqualTo(30_000);
		assertThat(ledgerTotal(to)).isEqualTo(5_000);
		assertThat(reversalCount(outLeg)).isEqualTo(1);
		assertThat(reversalCount(inLeg)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry WHERE ledger_account = 'TRANSFER_CLEARING'
				""", Long.class)).isZero();
	}

	/** 요청한 사람은 승인도 반려도 할 수 없다. 요청은 그대로 남는다. */
	@Test
	void requesterCannotDecide() {
		Long account = openWithBalance(10_000);
		Long correction = correctionService.request(
				accountService.deposit(account, 1_000, newKey()).value().getId(), "테스트", MAKER).id();

		assertThatThrownBy(() -> correctionService.approve(correction, MAKER, null))
				.isInstanceOf(SelfApprovalException.class);
		assertThatThrownBy(() -> correctionService.reject(correction, MAKER, "취소"))
				.isInstanceOf(SelfApprovalException.class);
		assertThat(statusOf(correction)).isEqualTo(CorrectionStatus.REQUESTED);
		assertThat(balance(account)).isEqualTo(11_000);
	}

	/** 코드를 우회해 SQL 로 스스로 승인해도 DB 제약(ck_correction_request_four_eyes)이 막는다. */
	@Test
	void databaseRejectsSelfApproval() {
		Long account = openWithBalance(10_000);
		Long correction = correctionService.request(
				accountService.deposit(account, 1_000, newKey()).value().getId(), "테스트", MAKER).id();

		assertThatThrownBy(() -> jdbcTemplate.update("""
				UPDATE correction_request SET status = 'APPROVED', decided_by = requested_by, decided_at = SYSTIMESTAMP
				WHERE id = ?
				""", correction))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("CK_CORRECTION_REQUEST_FOUR_EYES");
	}

	/** 한 거래에 열린 요청은 하나. 반려되면 다시 요청할 수 있다. */
	@Test
	void onlyOneOpenRequestPerTransaction() {
		Long account = openWithBalance(10_000);
		Long tx = accountService.deposit(account, 1_000, newKey()).value().getId();
		Long first = correctionService.request(tx, "첫 요청", MAKER).id();

		assertThatThrownBy(() -> correctionService.request(tx, "또 요청", CHECKER))
				.isInstanceOf(CorrectionConflictException.class)
				.extracting("code").isEqualTo("CORRECTION_ALREADY_REQUESTED");

		CorrectionView rejected = correctionService.reject(first, CHECKER, "사유를 구체적으로 적어 주세요");
		assertThat(rejected.status()).isEqualTo(CorrectionStatus.REJECTED);
		assertThat(rejected.decisionComment()).isEqualTo("사유를 구체적으로 적어 주세요");
		assertThat(rejected.reversalTransactionIds()).isEmpty();
		assertThat(balance(account)).isEqualTo(11_000);

		assertThat(correctionService.request(tx, "다시 요청", MAKER).status()).isEqualTo(CorrectionStatus.REQUESTED);
	}

	/** 정정된 거래는 다시 요청할 수 없고, 결정된 요청은 다시 결정할 수 없다. */
	@Test
	void reversedTransactionAndDecidedRequestAreFinal() {
		Long account = openWithBalance(10_000);
		Long tx = accountService.deposit(account, 1_000, newKey()).value().getId();
		Long correction = correctionService.request(tx, "테스트", MAKER).id();
		correctionService.approve(correction, CHECKER, null);

		assertThatThrownBy(() -> correctionService.approve(correction, "third-admin", null))
				.isInstanceOf(CorrectionConflictException.class)
				.extracting("code").isEqualTo("CORRECTION_ALREADY_DECIDED");
		assertThatThrownBy(() -> correctionService.request(tx, "또", MAKER))
				.isInstanceOf(CorrectionConflictException.class)
				.extracting("code").isEqualTo("ALREADY_REVERSED");
		assertThat(reversalCount(tx)).isEqualTo(1);
		assertThat(balance(account)).isEqualTo(10_000);
	}

	/** 해지 지급과 역분개는 정정 대상이 아니다. 없는 거래는 404 용 예외다. */
	@Test
	void rejectsUncorrectableTransactions() {
		Long account = openWithBalance(10_000);
		Long tx = accountService.deposit(account, 1_000, newKey()).value().getId();
		Long reversal = correctionService.approve(correctionService.request(tx, "테스트", MAKER).id(), CHECKER, null)
				.reversalTransactionIds().getFirst();
		Long closed = openWithBalance(5_000);
		Long payout = accountService.close(closed, newKey()).value().payout().getId();

		assertThatThrownBy(() -> correctionService.request(reversal, "되돌림의 되돌림", MAKER))
				.isInstanceOf(NotCorrectableException.class);
		assertThatThrownBy(() -> correctionService.request(payout, "해지 취소", MAKER))
				.isInstanceOf(NotCorrectableException.class);
		assertThatThrownBy(() -> correctionService.request(Long.MAX_VALUE, "없음", MAKER))
				.isInstanceOf(TransactionNotFoundException.class);
	}

	/**
	 * 고객이 이미 돈을 써서 되돌릴 잔액이 없으면 승인이 실패한다. 한 트랜잭션이라 요청 상태도 REQUESTED 로 돌아간다.
	 * (먼저 바꾼 요청 상태 UPDATE 까지 롤백된다)
	 */
	@Test
	void approvalFailsWhenBalanceIsInsufficient() {
		Long account = openWithBalance(0);
		Long tx = accountService.deposit(account, 10_000, newKey()).value().getId();
		accountService.withdraw(account, 8_000, newKey());
		Long correction = correctionService.request(tx, "잘못 입금", MAKER).id();

		assertThatThrownBy(() -> correctionService.approve(correction, CHECKER, null))
				.isInstanceOf(InsufficientBalanceException.class);
		assertThat(statusOf(correction)).isEqualTo(CorrectionStatus.REQUESTED);
		assertThat(balance(account)).isEqualTo(2_000);
		assertThat(reversalCount(tx)).isZero();
	}

	/** 해지된 계좌의 거래는 승인할 수 없다(해지 계좌는 잔액 0이어야 한다). */
	@Test
	void approvalFailsForClosedAccount() {
		Long account = openWithBalance(0);
		Long tx = accountService.withdraw(openWithBalanceFor(account, 10_000), 3_000, newKey()).value().getId();
		Long correction = correctionService.request(tx, "출금 취소", MAKER).id();
		accountService.close(account, newKey());

		assertThatThrownBy(() -> correctionService.approve(correction, CHECKER, null))
				.isInstanceOf(AccountClosedException.class);
		assertThat(statusOf(correction)).isEqualTo(CorrectionStatus.REQUESTED);
	}

	private Long openWithBalanceFor(Long account, long amount) {
		accountService.deposit(account, amount, newKey());
		return account;
	}

	/**
	 * 두 관리자가 같은 요청을 동시에 승인·반려하면 하나만 성공한다(@Version 낙관적 락 또는 상태 확인).
	 * 진 쪽은 CORRECTION_ALREADY_DECIDED 이고, 역분개는 승인이 이겼을 때만 한 건 생긴다.
	 */
	@Test
	void concurrentDecisionsOnlyOneWins() throws Exception {
		for (int round = 0; round < 5; round++) {
			Long account = openWithBalance(10_000);
			Long tx = accountService.deposit(account, 1_000, newKey()).value().getId();
			Long correction = correctionService.request(tx, "동시 결정", MAKER).id();

			CountDownLatch start = new CountDownLatch(1);
			List<Callable<CorrectionView>> tasks = List.of(
					() -> { start.await(); return correctionService.approve(correction, CHECKER, null); },
					() -> { start.await(); return correctionService.reject(correction, "other-checker", "반려"); });
			ExecutorService executor = Executors.newFixedThreadPool(2);
			List<Future<CorrectionView>> futures = new ArrayList<>();
			try {
				tasks.forEach(task -> futures.add(executor.submit(task)));
				start.countDown();
				int succeeded = 0;
				for (Future<CorrectionView> future : futures) {
					try {
						future.get();
						succeeded++;
					} catch (ExecutionException e) {
						assertThat(e.getCause()).isInstanceOf(CorrectionConflictException.class);
						assertThat(((CorrectionConflictException) e.getCause()).getCode()).isEqualTo("CORRECTION_ALREADY_DECIDED");
					}
				}
				assertThat(succeeded).isEqualTo(1);
			} finally {
				executor.shutdown();
			}

			CorrectionStatus status = statusOf(correction);
			assertThat(reversalCount(tx)).isEqualTo(status == CorrectionStatus.APPROVED ? 1 : 0);
			assertThat(balance(account)).isEqualTo(status == CorrectionStatus.APPROVED ? 10_000 : 11_000);
		}
	}

	/** HTTP: 결정자는 토큰의 아이디다. 같은 토큰으로 승인하면 403, 다른 관리자 토큰이면 200. */
	@Test
	void httpFlowUsesTokenSubjectAsDecider() throws Exception {
		Long account = openWithBalance(10_000);
		Long tx = accountService.deposit(account, 5_000, newKey()).value().getId();
		String maker = "Bearer " + tokenService.issueAdminToken(MAKER).value();
		String checker = "Bearer " + tokenService.issueAdminToken(CHECKER).value();

		String body = mockMvc.perform(post("/admin/corrections").header(HttpHeaders.AUTHORIZATION, maker)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"transactionId\": " + tx + ", \"reason\": \"금액 오입력\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("REQUESTED"))
				.andExpect(jsonPath("$.requestedBy").value(MAKER))
				.andExpect(jsonPath("$.transactionType").value("DEPOSIT"))
				.andExpect(jsonPath("$.amount").value(5000))
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();

		mockMvc.perform(post("/admin/corrections/" + id + "/approval").header(HttpHeaders.AUTHORIZATION, maker))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SELF_APPROVAL_NOT_ALLOWED"));
		mockMvc.perform(post("/admin/corrections/" + id + "/rejection").header(HttpHeaders.AUTHORIZATION, checker)
						.contentType(MediaType.APPLICATION_JSON).content("{\"comment\": \" \"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/admin/corrections/" + id + "/approval").header(HttpHeaders.AUTHORIZATION, checker))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.decidedBy").value(CHECKER))
				.andExpect(jsonPath("$.reversalTransactionIds.length()").value(1));
		mockMvc.perform(post("/admin/corrections/" + id + "/approval").header(HttpHeaders.AUTHORIZATION, checker))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CORRECTION_ALREADY_DECIDED"));

		mockMvc.perform(get("/admin/corrections?status=APPROVED&size=100").header(HttpHeaders.AUTHORIZATION, checker))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.id == " + id + ")].accountId").value(account.intValue()));
		// 거래내역에 역분개가 나가는 돈(OUT)으로 보이고, 무엇을 되돌렸는지 알려 준다.
		mockMvc.perform(get("/accounts/" + account + "/transactions").header(HttpHeaders.AUTHORIZATION, checker))
				.andExpect(jsonPath("$.transactions[0].type").value("REVERSAL_OUT"))
				.andExpect(jsonPath("$.transactions[0].direction").value("OUT"))
				.andExpect(jsonPath("$.transactions[0].reversalOfId").value(tx.intValue()))
				.andExpect(jsonPath("$.transactions[0].balanceAfter").value(10000));
		// 감사 기록에 정정 승인과 대상 계좌가 남는다.
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_log WHERE action = 'CORRECTION_APPROVE' AND target_account_id = ? AND actor_username = ?",
				Long.class, account, CHECKER)).isEqualTo(1);
	}

	/** 고객은 정정 API 를 쓸 수 없다. */
	@Test
	void customerCannotUseCorrectionApi() throws Exception {
		String customer = "Bearer " + tokenService.issueCustomerToken(1L, "somecustomer", "고객").value();
		mockMvc.perform(get("/admin/corrections").header(HttpHeaders.AUTHORIZATION, customer))
				.andExpect(status().isForbidden());
	}

}
