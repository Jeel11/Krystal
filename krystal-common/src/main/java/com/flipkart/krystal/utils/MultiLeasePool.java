package com.flipkart.krystal.utils;

import static java.lang.Math.max;

import java.util.Deque;
import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MultiLeasePool<T> implements AutoCloseable {

  private final Supplier<T> factory;

  private final int maxActiveLeasesPerObject;
  private final Consumer<T> destroyer;
  private double peakAvgActiveLeasesPerObject;
  private int maxPoolSize;

  private final Deque<PooledObject> queue = new LinkedList<>();
  private volatile boolean closed;

  public MultiLeasePool(Supplier<T> factory, int maxActiveLeasesPerObject, Consumer<T> destroyer) {
    this.factory = factory;
    this.maxActiveLeasesPerObject = maxActiveLeasesPerObject;
    this.destroyer = destroyer;
  }

  public final synchronized Lease lease() {
    if (closed) {
      throw new IllegalStateException("MultiLeasePool already closed");
    }
    PooledObject pooledObject = queue.poll();
    int count = queue.size();
    while (pooledObject != null
        && count-- > 0
        && (pooledObject.shouldDelete()
            || pooledObject.activeLeases() == maxActiveLeasesPerObject)) {
      if (!pooledObject.shouldDelete()) {
        queue.add(pooledObject);
      } else {
        destroyer.accept(pooledObject.ref());
      }
      pooledObject = queue.poll();
    }
    if (pooledObject == null || pooledObject.activeLeases() == maxActiveLeasesPerObject) {
      pooledObject = addNewForLeasing();
    } else {
      pooledObject.incrementActiveLeases();
    }
    queue.add(pooledObject);
    peakAvgActiveLeasesPerObject =
        max(
            peakAvgActiveLeasesPerObject,
            queue.stream().mapToInt(PooledObject::activeLeases).average().orElse(0));
    return new Lease(pooledObject);
  }

  private synchronized void giveBack(PooledObject pooledObject) {
    pooledObject.decrementActiveLeases();
  }

  private PooledObject addNewForLeasing() {
    T t = factory.get();
    PooledObject pooledObject = new PooledObject(t);
    pooledObject.incrementActiveLeases();
    queue.add(pooledObject);
    maxPoolSize = max(maxPoolSize, queue.size());
    return pooledObject;
  }

  public final int maxActiveLeasesPerObject() {
    return maxActiveLeasesPerObject;
  }

  public final double peakAvgActiveLeasesPerObject() {
    return peakAvgActiveLeasesPerObject;
  }

  public final int maxPoolSize() {
    return maxPoolSize;
  }

  @Override
  public void close() {
    this.closed = true;
    PooledObject pooledObject;
    while ((pooledObject = queue.pollLast()) != null) {
      destroyer.accept(pooledObject.ref());
    }
  }

  public final class Lease implements AutoCloseable {

    private PooledObject pooledObject;

    private Lease(PooledObject pooledObject) {
      this.pooledObject = pooledObject;
    }

    public T get() {
      if (pooledObject == null) {
        throw new IllegalStateException("Lease already released");
      }
      return pooledObject.ref();
    }

    @Override
    public void close() {
      if (pooledObject != null) {
        giveBack(pooledObject);
        pooledObject = null;
      }
    }
  }

  private final class PooledObject {

    private final T ref;
    private int activeLeases = 0;
    private int markForDeletion;

    private PooledObject(T ref) {
      this.ref = ref;
    }

    public T ref() {
      return ref;
    }

    public int activeLeases() {
      return activeLeases;
    }

    public void incrementActiveLeases() {
      activeLeases++;
    }

    public void decrementActiveLeases() {
      activeLeases--;
      if (activeLeases() == 0 && maxActiveLeasesPerObject() > 1) {
        markForDeletion++;
      } else {
        markForDeletion = 0;
      }
    }

    public boolean shouldDelete() {
      return markForDeletion
          > 100; // This number is a bit random - need to find a better way to calibrate this.
    }
  }
}
