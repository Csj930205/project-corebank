package com.corebank.db;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.AccountService;
import com.corebank.support.SchemaOwner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB 계정 분리. 앱은 테이블 소유자가 아닌 앱 계정으로 접속하고, 그 계정은 R__app_user_grants.sql 이 준 권한만 갖는다.
 * 앱 계정이 탈취되어도 트리거를 끄거나 테이블을 비우거나 지울 수 없다는 것을 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DatabaseAccountSeparationIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SchemaOwner owner;

	@Autowired
	private AccountService accountService;

	/** 앱은 앱 계정으로 접속하고, 기본 스키마만 소유자 스키마로 바꿔 테이블을 찾는다. */
	@Test
	void appConnectsAsAppUserWithOwnerSchema() {
		assertThat(jdbcTemplate.queryForObject("SELECT USER FROM dual", String.class)).isEqualTo("COREBANK_APP");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual", String.class)).isEqualTo("COREBANK");
	}

	/** 앱 계정에는 테이블도 없고, 시스템 권한은 접속(CREATE SESSION)뿐이다. */
	@Test
	void appUserOwnsNothingAndHasOnlySessionPrivilege() {
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_objects", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForList("SELECT privilege FROM user_sys_privs", String.class))
				.containsExactly("CREATE SESSION");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_role_privs", Long.class)).isZero();
	}

	/** 소유자만 할 수 있는 DDL: 원장 트리거 끄기, 테이블 비우기·지우기. 앱 계정은 모두 거부된다. */
	@Test
	void appUserCannotRunDdlOnOwnerObjects() {
		assertDenied("ALTER TRIGGER trg_ledger_transaction_append_only DISABLE");
		assertDenied("ALTER TRIGGER trg_audit_log_append_only DISABLE");
		assertDenied("TRUNCATE TABLE audit_log");
		assertDenied("TRUNCATE TABLE ledger_entry");
		assertDenied("DROP TABLE ledger_transaction");
		assertDenied("CREATE TABLE app_scratch (id NUMBER)");
	}

	/** 계좌는 잔액·상태·해지 시각만 바꿀 수 있다. 계좌번호·이름·개설일을 바꾸거나 계좌를 지울 수 없다. */
	@Test
	void appUserCanOnlyUpdateMutableAccountColumns() {
		Long id = accountService.open("열권한").getId();

		assertDeniedUpdate("UPDATE account SET account_number = '0000000000000' WHERE id = ?", id);
		assertDeniedUpdate("UPDATE account SET owner_name = '바꿈' WHERE id = ?", id);
		assertDeniedUpdate("UPDATE account SET created_at = SYSTIMESTAMP WHERE id = ?", id);
		assertDeniedUpdate("DELETE FROM account WHERE id = ?", id);
		assertThat(jdbcTemplate.update("UPDATE account SET balance = balance WHERE id = ?", id)).isEqualTo(1);
	}

	/** 소유자의 모든 테이블에 앱 권한이 있다. 새 테이블을 만들고 R__app_user_grants.sql 에 빠뜨리면 여기서 깨진다. */
	@Test
	void everyOwnerTableIsGrantedToAppUser() {
		List<String> ungranted = owner.jdbc().queryForList("""
				SELECT t.table_name FROM user_tables t
				WHERE t.table_name <> 'flyway_schema_history'
				  AND NOT EXISTS (SELECT 1 FROM user_tab_privs_made p
				                  WHERE p.table_name = t.table_name AND p.grantee = 'COREBANK_APP' AND p.privilege = 'SELECT')
				""", String.class);
		List<String> ungrantedSequences = owner.jdbc().queryForList("""
				SELECT s.sequence_name FROM user_sequences s
				WHERE NOT EXISTS (SELECT 1 FROM user_tab_privs_made p
				                  WHERE p.table_name = s.sequence_name AND p.grantee = 'COREBANK_APP')
				""", String.class);

		assertThat(ungranted).isEmpty();
		assertThat(ungrantedSequences).isEmpty();
	}

	private void assertDenied(String ddl) {
		// 권한 없음(ORA-41900, 예전 버전은 ORA-01031) 또는 앱 계정에게는 보이지 않는 객체(ORA-00942·ORA-04080)
		assertThatThrownBy(() -> jdbcTemplate.execute(ddl))
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageMatching("(?s).*ORA-(41900|01031|00942|04080).*");
	}

	private void assertDeniedUpdate(String sql, Object... args) {
		assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ORA-41900");
	}

}
