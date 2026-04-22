package com.cube.simple;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"job.scheduling.enabled=false"
})
class SimpleApplicationTests {

	@Test
	void contextLoads() {
	}

}
