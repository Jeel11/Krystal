package com.flipkart.krystal.vajramexecutor.krystex.testharness;

import static java.util.concurrent.CompletableFuture.completedFuture;

import com.flipkart.krystal.data.Errable;
import com.flipkart.krystal.data.Facets;
import com.flipkart.krystal.krystex.commands.Flush;
import com.flipkart.krystal.krystex.commands.ForwardBatch;
import com.flipkart.krystal.krystex.commands.ForwardGranule;
import com.flipkart.krystal.krystex.commands.KryonCommand;
import com.flipkart.krystal.krystex.kryon.AbstractKryonDecorator;
import com.flipkart.krystal.krystex.kryon.BatchResponse;
import com.flipkart.krystal.krystex.kryon.Kryon;
import com.flipkart.krystal.krystex.kryon.KryonDefinition;
import com.flipkart.krystal.krystex.kryon.KryonExecutor;
import com.flipkart.krystal.krystex.kryon.KryonId;
import com.flipkart.krystal.krystex.kryon.KryonResponse;
import com.flipkart.krystal.krystex.request.RequestId;
import com.flipkart.krystal.vajram.VajramID;
import com.flipkart.krystal.vajram.VajramRequest;
import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * VajramPrimer is a custom Kryon Decorator which enables mocking the request and response of a
 * vajram at runtime. When a kryon is decorated with vajram primer, it overrides the actual
 * execution logic of the vajram and replaces it with the provided execution stubs.
 */
public class VajramPrimer extends AbstractKryonDecorator {

  private final ImmutableMap<Facets, Errable<Object>> executionStubs;
  private final VajramID decoratedVajramId;
  private final boolean failIfMockMissing;
  private @MonotonicNonNull DecoratedKryon decoratedKryon;

  public <T> VajramPrimer(
      VajramID mockedVajramId, Map<VajramRequest<T>, Errable<T>> stubs, boolean failIfMockMissing) {
    this.decoratedVajramId = mockedVajramId;
    this.failIfMockMissing = failIfMockMissing;
    Map<Facets, Errable<Object>> mocks = new LinkedHashMap<>(stubs.size());
    stubs.forEach(
        (req, resp) -> {
          //noinspection unchecked
          mocks.put(req.toFacetValues(), (Errable<Object>) resp);
        });
    this.executionStubs = ImmutableMap.copyOf(mocks);
  }

  @Override
  public Kryon<KryonCommand, KryonResponse> decorateKryon(
      Kryon<KryonCommand, KryonResponse> kryon, KryonExecutor kryonExecutor) {
    if (decoratedKryon == null) {
      decoratedKryon = new DecoratedKryon(kryon, kryonExecutor);
    }
    return decoratedKryon;
  }

  private class DecoratedKryon implements Kryon<KryonCommand, KryonResponse> {

    private final Kryon<KryonCommand, KryonResponse> kryon;
    private final KryonExecutor kryonExecutor;

    private DecoratedKryon(Kryon<KryonCommand, KryonResponse> kryon, KryonExecutor kryonExecutor) {
      this.kryon = kryon;
      this.kryonExecutor = kryonExecutor;
    }

    @Override
    public void executeCommand(Flush flushCommand) {
      validate(flushCommand.kryonId());
      kryon.executeCommand(flushCommand);
    }

    @Override
    public CompletableFuture<KryonResponse> executeCommand(KryonCommand kryonCommand) {
      KryonId kryonId = kryonCommand.kryonId();
      validate(kryonId);
      if (kryonCommand instanceof ForwardBatch forwardBatch) {
        return getPrimedResponse(kryon, forwardBatch, kryonId, kryonExecutor);
      } else if (kryonCommand instanceof ForwardGranule) {
        throw new UnsupportedOperationException(
            "VajramPrimer does not support KryonExecStrategy GRANULAR. Please use BATCH instead");
      }
      return kryon.executeCommand(kryonCommand);
    }

    @Override
    public KryonDefinition getKryonDefinition() {
      return kryon.getKryonDefinition();
    }

    private void validate(KryonId kryonId) {
      String vajramId = decoratedVajramId.vajramId();
      String kryondId = kryonId.value();
      if (!Objects.equals(kryondId, vajramId)) {
        throw new AssertionError(
            "Vajram mocker for %s received command for %s".formatted(vajramId, kryondId));
      }
    }
  }

  private CompletableFuture<KryonResponse> getPrimedResponse(
      Kryon<KryonCommand, KryonResponse> kryon,
      ForwardBatch forwardBatch,
      KryonId kryonId,
      KryonExecutor kryonExecutor) {
    Map<RequestId, Errable<Object>> finalResponses = new LinkedHashMap<>();
    Set<RequestId> unmockedRequestIds = new LinkedHashSet<>();
    for (Entry<RequestId, Facets> entry : forwardBatch.executableRequests().entrySet()) {
      RequestId requestId = entry.getKey();
      Facets facets = entry.getValue();
      Errable<Object> mockedResponse = executionStubs.get(facets);
      if (mockedResponse == null) {
        if (failIfMockMissing) {
          throw new IllegalStateException(
              "Could not find mocked response for inputs %s of kryon %s"
                  .formatted(facets, kryonId));
        } else {
          unmockedRequestIds.add(requestId);
        }
      } else {
        finalResponses.put(requestId, mockedResponse);
      }
    }
    LinkedHashMap<RequestId, Facets> unmockedRequests =
        new LinkedHashMap<>(forwardBatch.executableRequests());
    unmockedRequests.keySet().retainAll(unmockedRequestIds);
    try {
      if (!unmockedRequests.isEmpty()) {
        CompletableFuture<KryonResponse> forwardedRequestsResult =
            kryon.executeCommand(
                new ForwardBatch(
                    forwardBatch.kryonId(),
                    forwardBatch.inputNames(),
                    ImmutableMap.copyOf(unmockedRequests),
                    forwardBatch.dependantChain(),
                    forwardBatch.skippedRequests()));
        return forwardedRequestsResult.handle(
            (kryonResponse, throwable) -> {
              if (kryonResponse instanceof BatchResponse batchResponse) {
                finalResponses.putAll(batchResponse.responses());
              } else {
                Errable<Object> error =
                    throwable != null
                        ? Errable.withError(throwable)
                        : Errable.withError(
                            new AssertionError(
                                "Unknown KryonResponse type of response %s from kryon %s"
                                    .formatted(kryonResponse, kryonId)));
                for (RequestId unmockedRequestId : unmockedRequestIds) {
                  finalResponses.put(unmockedRequestId, error);
                }
              }
              return new BatchResponse(ImmutableMap.copyOf(finalResponses));
            });
      } else {
        return completedFuture(new BatchResponse(ImmutableMap.copyOf(finalResponses)));
      }
    } finally {
      flushDependencies(kryon, forwardBatch, kryonExecutor);
    }
  }

  private void flushDependencies(
      Kryon<KryonCommand, KryonResponse> kryon,
      ForwardBatch forwardBatch,
      KryonExecutor kryonExecutor) {
    KryonDefinition kryonDefinition = kryon.getKryonDefinition();
    KryonId kryonId = kryonDefinition.kryonId();

    kryonDefinition
        .dependencyKryons()
        .forEach(
            (depName, depKryon) -> {
              executeCommand(
                  new Flush(depKryon, forwardBatch.dependantChain().extend(kryonId, depName)),
                  kryonExecutor);
            });
  }
}
