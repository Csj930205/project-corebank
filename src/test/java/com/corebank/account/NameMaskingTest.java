package com.corebank.account;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class NameMaskingTest {

	@ParameterizedTest
	@CsvSource({
			"홍길동, 홍*동",
			"남궁민수, 남**수",
			"이산, 이*",
			"김, 김",
			"' 홍길동 ', 홍*동",
			"Kim Lee, K*****e",
	})
	void masksMiddle(String name, String expected) {
		assertThat(NameMasking.mask(name)).isEqualTo(expected);
	}

	/** 이모지·확장 한자처럼 두 char 인 글자도 한 글자로 센다(반쪽 글자가 남지 않는다). */
	@ParameterizedTest
	@CsvSource({"𠀀길𠀀, 𠀀*𠀀", "😀😀, 😀*"})
	void countsCodePoints(String name, String expected) {
		assertThat(NameMasking.mask(name)).isEqualTo(expected);
	}

}
