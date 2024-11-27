package com.flipkart.krystal.krystex.caching;

import static com.flipkart.krystal.data.Errable.computeErrableFrom;
import static com.flipkart.krystal.krystex.kryon.KryonExecutor.GraphTraversalStrategy.BREADTH;
import static com.flipkart.krystal.krystex.kryon.KryonExecutor.GraphTraversalStrategy.DEPTH;
import static com.flipkart.krystal.krystex.kryon.KryonExecutor.KryonExecStrategy.BATCH;
import static com.flipkart.krystal.krystex.kryon.KryonExecutor.KryonExecStrategy.GRANULAR;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Collections.emptySet;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.assertThat;

import com.flipkart.krystal.concurrent.SingleThreadExecutor;
import com.flipkart.krystal.concurrent.SingleThreadExecutorsPool;
import com.flipkart.krystal.data.Facets;
import com.flipkart.krystal.krystex.ComputeLogicDefinition;
import com.flipkart.krystal.krystex.LogicDefinitionRegistry;
import com.flipkart.krystal.krystex.OutputLogicDefinition;
import com.flipkart.krystal.krystex.kryon.KryonDefinition;
import com.flipkart.krystal.krystex.kryon.KryonDefinitionRegistry;
import com.flipkart.krystal.krystex.kryon.KryonExecutionConfig;
import com.flipkart.krystal.krystex.kryon.KryonExecutor;
import com.flipkart.krystal.krystex.kryon.KryonExecutor.GraphTraversalStrategy;
import com.flipkart.krystal.krystex.kryon.KryonExecutor.KryonExecStrategy;
import com.flipkart.krystal.krystex.kryon.KryonExecutorConfig;
import com.flipkart.krystal.krystex.kryon.KryonExecutorConfig.KryonExecutorConfigBuilder;
import com.flipkart.krystal.krystex.kryon.KryonId;
import com.flipkart.krystal.krystex.kryon.KryonLogicId;
import com.flipkart.krystal.krystex.kryondecoration.KryonDecoratorConfig;
import com.flipkart.krystal.pooling.Lease;
import com.flipkart.krystal.pooling.LeaseUnavailableException;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RequestLevelCacheTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(1);

  private static SingleThreadExecutorsPool EXEC_POOL;

  private KryonDefinitionRegistry kryonDefinitionRegistry;
  private LogicDefinitionRegistry logicDefinitionRegistry;
  private RequestLevelCache requestLevelCache;
  private KryonExecutor kryonExecutor;
  private Lease<SingleThreadExecutor> executorLease;

  @BeforeAll
  static void beforeAll() {
    EXEC_POOL = new SingleThreadExecutorsPool("RequestLevelCacheTest", 4);
  }

  @BeforeEach
  void setUp() throws LeaseUnavailableException {
    this.requestLevelCache = new RequestLevelCache();
    this.logicDefinitionRegistry = new LogicDefinitionRegistry();
    this.kryonDefinitionRegistry = new KryonDefinitionRegistry(logicDefinitionRegistry);
    this.executorLease = EXEC_POOL.lease();
  }

  @AfterEach
  void tearDown() {
    Optional.ofNullable(kryonExecutor).ifPresent(KryonExecutor::close);
    executorLease.close();
  }

  @ParameterizedTest
  @MethodSource("executorConfigsToTest")
  void multiRequestExecution_withCache_cacheHitSuccess(
      KryonExecStrategy kryonExecStrategy, GraphTraversalStrategy graphTraversalStrategy)
      throws LeaseUnavailableException {
    // This is redundant. This should Ideally move to a paramterized @BeforeEach method or after
    // parametrizing this at the test class level.
    // This is currently not supported in jupiter-junit:5.9.x.
    // It is planned to be supported in jupiter-junit:5.10
    // (Ref: https://github.com/junit-team/junit5/issues/878)
    // Move this to the @BeforeEach method after 5.10 is released.
    this.kryonExecutor = getKryonExecutor(kryonExecStrategy, graphTraversalStrategy, true);
    LongAdder adder = new LongAdder();
    KryonDefinition kryonDefinition =
        kryonDefinitionRegistry.newKryonDefinition(
            "kryon",
            emptySet(),
            newComputeLogic(
                    "kryonLogic",
                    emptySet(),
                    dependencyValues -> {
                      adder.increment();
                      return "computed_value";
                    })
                .kryonLogicId());
    CompletableFuture<Object> future1 =
        kryonExecutor.executeKryon(
            kryonDefinition.kryonId(),
            Facets.empty(),
            KryonExecutionConfig.builder().executionId("req_1").build());
    CompletableFuture<Object> future2 =
        kryonExecutor.executeKryon(
            kryonDefinition.kryonId(),
            Facets.empty(),
            KryonExecutionConfig.builder().executionId("req_2").build());

    kryonExecutor.close();
    assertThat(future1).succeedsWithin(TIMEOUT).isEqualTo("computed_value");
    assertThat(future2).succeedsWithin(TIMEOUT).isEqualTo("computed_value");
    assertThat(adder.sum()).isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("executorConfigsToTest")
  void multiRequestExecution_withoutCache_cacheHitFailForBatch(
      KryonExecStrategy kryonExecStrategy, GraphTraversalStrategy graphTraversalStrategy)
      throws LeaseUnavailableException {
    // This is redundant. This should Ideally move to a paramterized @BeforeEach method or after
    // parametrizing this at the test class level.
    // This is currently not supported in jupiter-junit:5.9.x.
    // It is planned to be supported in jupiter-junit:5.10
    // (Ref: https://github.com/junit-team/junit5/issues/878)
    // Move this to the @BeforeEach method after 5.10 is released.
    this.kryonExecutor = getKryonExecutor(kryonExecStrategy, graphTraversalStrategy, false);
    LongAdder adder = new LongAdder();
    KryonDefinition kryonDefinition =
        kryonDefinitionRegistry.newKryonDefinition(
            "kryon",
            emptySet(),
            newComputeLogic(
                    "kryonLogic",
                    emptySet(),
                    dependencyValues -> {
                      adder.increment();
                      return "computed_value";
                    })
                .kryonLogicId());
    CompletableFuture<Object> future1 =
        kryonExecutor.executeKryon(
            kryonDefinition.kryonId(),
            Facets.empty(),
            KryonExecutionConfig.builder().executionId("req_1").build());
    CompletableFuture<Object> future2 =
        kryonExecutor.executeKryon(
            kryonDefinition.kryonId(),
            Facets.empty(),
            KryonExecutionConfig.builder().executionId("req_2").build());

    kryonExecutor.close();
    assertThat(future1).succeedsWithin(TIMEOUT).isEqualTo("computed_value");
    assertThat(future2).succeedsWithin(TIMEOUT).isEqualTo("computed_value");
    if (BATCH.equals(kryonExecStrategy)) {
      assertThat(adder.sum()).isEqualTo(2);
    } else {
      assertThat(adder.sum()).isEqualTo(1);
    }
  }

  private KryonExecutor getKryonExecutor(
      KryonExecStrategy kryonExecStrategy,
      GraphTraversalStrategy graphTraversalStrategy,
      boolean withCache)
      throws LeaseUnavailableException {
    KryonExecutorConfigBuilder configBuilder =
        KryonExecutorConfig.builder()
            .singleThreadExecutor(executorLease.get())
            .kryonExecStrategy(kryonExecStrategy)
            .graphTraversalStrategy(graphTraversalStrategy);
    if (withCache) {
      configBuilder
          .requestScopedKryonDecoratorConfig(
              RequestLevelCache.DECORATOR_TYPE,
              new KryonDecoratorConfig(
                  RequestLevelCache.DECORATOR_TYPE,
                  _c -> true,
                  _c -> RequestLevelCache.DECORATOR_TYPE,
                  _c -> requestLevelCache))
          .build();
    }
    return new KryonExecutor(kryonDefinitionRegistry, configBuilder.build(), "test");
  }

  private <T> OutputLogicDefinition<T> newComputeLogic(
      String kryonId, Set<String> inputs, Function<Facets, T> logic) {
    ComputeLogicDefinition<T> def =
        new ComputeLogicDefinition<>(
            new KryonLogicId(new KryonId(kryonId), kryonId),
            inputs,
            inputsList ->
                inputsList.stream()
                    .collect(toImmutableMap(identity(), computeErrableFrom(logic)))
                    .entrySet()
                    .stream()
                    .collect(toImmutableMap(Entry::getKey, e -> e.getValue().toFuture())),
            ImmutableMap.of());

    logicDefinitionRegistry.addOutputLogic(def);
    return def;
  }

  public static Stream<Arguments> executorConfigsToTest() {
    return Stream.of(
        Arguments.of(BATCH, DEPTH),
        Arguments.of(BATCH, BREADTH),
        Arguments.of(GRANULAR, DEPTH),
        Arguments.of(GRANULAR, BREADTH));
  }
}
