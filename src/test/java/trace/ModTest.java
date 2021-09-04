package trace;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModTest {

	public static int f(int a) {
		a = a + 1;
		a = a % 2;// should be a % 4
		return a;
	}

	@Test
	void pass() {
		assertEquals(f(0), 1);
	}

	@Test
	void fail() {
		assertEquals(f(2), 3);
	}

}
