package com.corebank.support;

import java.util.List;

/**
 * 일 마감 대사 테스트에서 원장을 일부러 틀어 놓을 때 쓴다.
 *
 * <p>원장은 DB 트리거(V12)가 수정·삭제를 막는다. 그런데 대사 배치는 "트리거를 우회한 변경도 찾아낸다" 가 존재 이유라,
 * 테스트는 테이블 소유자 계정으로 트리거를 잠시 끄고 조작한 뒤 바로 다시 켠다. 앱 계정은 원장에 UPDATE·DELETE 권한도,
 * 트리거를 끌 권한도 없으므로(R__app_user_grants.sql) 이 조작은 소유자만 할 수 있다. 앱 코드에는 우회 경로를 두지 않는다.
 *
 * <p>ALTER TRIGGER 는 DB 전체에 걸리므로 테스트가 병렬로 돌면 안 된다(지금은 한 JVM 에서 차례로 돈다).
 */
public final class LedgerTampering {

	private static final List<String> TRIGGERS = List.of(
			"trg_ledger_transaction_append_only",
			"trg_ledger_entry_append_only",
			"trg_transfer_append_only");

	private LedgerTampering() {
	}

	/** 소유자로 트리거를 끄고 SQL 을 실행한 뒤, 실패해도 반드시 다시 켠다. */
	public static void withoutAppendOnlyTriggers(SchemaOwner owner, String sql, Object... args) {
		TRIGGERS.forEach(t -> owner.jdbc().execute("ALTER TRIGGER " + t + " DISABLE"));
		try {
			owner.jdbc().update(sql, args);
		} finally {
			TRIGGERS.forEach(t -> owner.jdbc().execute("ALTER TRIGGER " + t + " ENABLE"));
		}
	}

}
