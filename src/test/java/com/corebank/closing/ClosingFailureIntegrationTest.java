package com.corebank.closing;

import com.corebank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 배치 도중 오류가 나면 마감 실행이 RUNNING 으로 남지 않고 FAILED 와 원인이 기록되는지 본다.
 * 저장소를 스파이로 바꿔 원장 점검 단계에서 DB 오류가 나게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClosingFailureIntegrationTest {

	@MockitoSpyBean
	private ClosingRepository closingRepository;

	@Autowired
	private ClosingService closingService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void failedStepMarksClosingFailedAndAllowsRerun() {
		LocalDate date = LocalDate.now().minusDays(50);
		doThrow(new DataAccessResourceFailureException("DB 연결 끊김(테스트)"))
				.when(closingRepository).insertClearingNotZero(anyLong(), any(LocalDateTime.class));

		DailyClosing failed = closingService.run(date, ClosingTrigger.MANUAL, null);

		assertThat(failed.status()).isEqualTo(ClosingStatus.FAILED);
		assertThat(failed.finishedAt()).isNotNull();
		assertThat(failed.errorMessage()).contains("DB 연결 끊김(테스트)");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM BATCH_JOB_EXECUTION WHERE job_execution_id = ?", String.class, failed.jobExecutionId()))
				.isEqualTo("FAILED");
		// 원장 점검 단계 이후(finishStep)는 실행되지 않았다.
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE job_execution_id = ? AND step_name = 'finishStep'
				""", Long.class, failed.jobExecutionId())).isZero();

		// RUNNING 이 남지 않았으므로 같은 날짜를 바로 다시 마감할 수 있다.
		org.mockito.Mockito.reset(closingRepository);
		DailyClosing rerun = closingService.run(date, ClosingTrigger.MANUAL, null);
		assertThat(rerun.status()).isEqualTo(ClosingStatus.BALANCED);
	}

}
