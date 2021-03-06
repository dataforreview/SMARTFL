package trace;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BadReturnTest {

	public int fact(int n) {
		if (n <= 0)
			return 1;
		if (n == 2)
			return 300000;// wrong
		// int ret = fact(n - 1);
		// return n * ret;
		return n * fact(n - 1);
	}

	@Test
	void pass1() {
		assertEquals(fact(0), 1);
	}

	@Test
	void pass2() {
		assertEquals(fact(1), 1);
	}

	@Test
	void fail() {
		assertEquals(fact(3), 6);
	}

}
