package com.flipkart.krystal.vajram.samples.calculator;

import static com.flipkart.krystal.vajram.VajramID.ofVajram;

import com.flipkart.krystal.executors.SingleThreadExecutor;
import com.flipkart.krystal.executors.ThreadPerRequestPool;
import com.flipkart.krystal.krystex.kryon.KryonExecutorConfig;
import com.flipkart.krystal.utils.Lease;
import com.flipkart.krystal.utils.LeaseUnavailableException;
import com.flipkart.krystal.vajramexecutor.krystex.KrystexVajramExecutor;
import com.flipkart.krystal.vajramexecutor.krystex.KrystexVajramExecutorConfig;
import com.flipkart.krystal.vajramexecutor.krystex.VajramKryonGraph;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Add2And3Test {
  private static ThreadPerRequestPool EXEC_POOL;

  @BeforeAll
  static void beforeAll() {
    EXEC_POOL = new ThreadPerRequestPool("RequestLevelCacheTest", 4);
  }

  private final VajramKryonGraph graph =
      VajramKryonGraph.builder().loadFromPackage(Add2And3.class.getPackageName()).build();

  private Lease<SingleThreadExecutor> executorLease;

  @BeforeEach
  void setUp() throws LeaseUnavailableException {
    this.executorLease = EXEC_POOL.lease();
  }

  @AfterEach
  void tearDown() {
    executorLease.close();
  }

  @Test
  void add2And3_success() {
    CompletableFuture<Integer> future;
    try (KrystexVajramExecutor krystexVajramExecutor =
        graph.createExecutor(
            KrystexVajramExecutorConfig.builder()
                .requestId("add2and3")
                .kryonExecutorConfigBuilder(
                    KryonExecutorConfig.builder().singleThreadExecutor(executorLease.get()))
                .build())) {
      future =
          krystexVajramExecutor.execute(
              ofVajram(Add2And3.class), Add2And3Request.builder().build());
    }
    Assertions.assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo(5);
  }
}
