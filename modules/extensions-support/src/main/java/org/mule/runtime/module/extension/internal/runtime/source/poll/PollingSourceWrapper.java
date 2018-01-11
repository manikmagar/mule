/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source.poll;

import static java.lang.String.format;
import static java.util.Comparator.naturalOrder;
import static java.util.Optional.ofNullable;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.store.ObjectStoreSettings.unmanagedPersistent;
import static org.mule.runtime.api.util.Preconditions.checkArgument;
import static org.mule.runtime.extension.api.runtime.source.PollContext.PollItemStatus.ACCEPTED;
import static org.mule.runtime.extension.api.runtime.source.PollContext.PollItemStatus.ALREADY_IN_PROCESS;
import static org.mule.runtime.extension.api.runtime.source.PollContext.PollItemStatus.FILTERED_BY_WATERMARK;
import static org.mule.runtime.extension.api.runtime.source.PollContext.PollItemStatus.SOURCE_STOPPING;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Mono.fromRunnable;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.scheduler.SchedulerConfig;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreException;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.api.store.ObjectStoreSettings;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.source.scheduler.Scheduler;
import org.mule.runtime.core.api.util.func.CheckedRunnable;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.source.PollContext;
import org.mule.runtime.extension.api.runtime.source.PollContext.PollItem;
import org.mule.runtime.extension.api.runtime.source.PollingSource;
import org.mule.runtime.extension.api.runtime.source.SourceCallback;
import org.mule.runtime.extension.api.runtime.source.SourceCallbackContext;
import org.mule.runtime.module.extension.internal.runtime.source.SourceWrapper;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

import javax.inject.Inject;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;

public class PollingSourceWrapper<T, A> extends SourceWrapper<T, A> {

  private static final Logger LOGGER = getLogger(PollingSourceWrapper.class);
  private static final String ITEM_RELEASER_CTX_VAR = "itemReleaser";
  private static final String WATERMARK_OS_KEY = "watermark";

  private final PollingSource<T, A> delegate;
  private final Scheduler scheduler;

  @Inject
  private LockFactory lockFactory;

  @Inject
  private ObjectStoreManager objectStoreManager;

  @Inject
  private SchedulerService schedulerService;

  private ObjectStore<Serializable> watermarkObjectStore;
  private ObjectStore<Serializable> inflightIdsObjectStore;
  private ObjectStore<Serializable> recentlyProcessedIds;

  private ComponentLocation componentLocation;
  private String flowName;
  private String keyPrefix;
  private final AtomicBoolean stopRequested = new AtomicBoolean(false);
  private org.mule.runtime.api.scheduler.Scheduler executor;

  public PollingSourceWrapper(PollingSource<T, A> delegate, Scheduler scheduler) {
    super(delegate);
    this.delegate = delegate;
    this.scheduler = scheduler;
  }

  @Override
  public void onStart(SourceCallback<T, A> sourceCallback) throws MuleException {
    delegate.onStart(sourceCallback);
    flowName = componentLocation.getRootContainerName();
    keyPrefix = "_pollingSource_" + flowName + "/";
    inflightIdsObjectStore = objectStoreManager.getOrCreateObjectStore(formatKey("inflight-ids"),
                                                                       ObjectStoreSettings.builder()
                                                                           .persistent(false)
                                                                           .maxEntries(1000)
                                                                           .entryTtl(60000L)
                                                                           .expirationInterval(20000L)
                                                                           .build());

    recentlyProcessedIds = objectStoreManager.getOrCreateObjectStore(formatKey("recently-processed-ids"),
                                                                     ObjectStoreSettings.builder()
                                                                         .persistent(true)
                                                                         .maxEntries(1000)
                                                                         .entryTtl(60000L)
                                                                         .expirationInterval(20000L)
                                                                         .build());


    watermarkObjectStore = objectStoreManager.getOrCreateObjectStore(formatKey("watermark"), unmanagedPersistent());
    executor = schedulerService.customScheduler(SchedulerConfig.config()
        .withMaxConcurrentTasks(1)
        .withWaitAllowed(true)
        .withName(formatKey("executor")));

    stopRequested.set(false);
    scheduler.schedule(executor, () -> poll(sourceCallback));
  }

  private String formatKey(String key) {
    return keyPrefix + key;
  }

  @Override
  public void onStop() {
    stopRequested.set(true);
    shutdownScheduler();
    try {
      delegate.onStop();
    } catch (Throwable t) {
      LOGGER.error(format("Found error while stopping source at location '%s'. %s", flowName, t.getMessage()), t);
    }
  }

  @Override
  public Publisher<Void> onTerminate(CoreEvent event, Map<String, Object> parameters, SourceCallbackContext context) {
    return releaseOnCallback(context);
  }

  @Override
  public Publisher<Void> onBackPressure(CoreEvent event, Map<String, Object> parameters, SourceCallbackContext context) {
    return releaseOnCallback(context);
  }

  private Publisher<Void> releaseOnCallback(SourceCallbackContext context) {
    return fromRunnable(() -> release(context));
  }

  private void poll(SourceCallback<T, A> sourceCallback) {
    if (isRequestedToStop()) {
      return;
    }

    withWatermarkLock(() -> {
      DefaultPollContext pollContext = new DefaultPollContext(sourceCallback, getCurrentWatermark());
      try {
        delegate.poll(pollContext);
        pollContext.getUpdatedWatermark().ifPresent(w -> updateWatermark(w, pollContext.getWatermarkComparator()));
      } catch (Throwable t) {
        LOGGER.error(format("Found exception trying to process item on source at flow '%s'. %s",
                            flowName, t.getMessage()),
                     t);
      }
    });
  }

  private int compareWatermarks(Serializable w1, Serializable w2, Comparator comparator) throws IllegalArgumentException {
    if (comparator == null) {
      if (w1 instanceof Serializable && w2 instanceof Serializable) {
        comparator = naturalOrder();
      } else {
        throw new IllegalStateException(format("Non comparable watermark values [%s, %s] were provided on source at flow '%s'. "
            + "Use comparable values or set a custom comparator. Watermark not updated.",
                                               w1, w2, flowName));

      }
    }

    return comparator.compare(w1, w2);
  }

  private class DefaultPollContext implements PollContext<T, A> {

    private final SourceCallback<T, A> sourceCallback;
    private Serializable currentWatermark;
    private Serializable updatedWatermark = null;
    private Comparator<Serializable> watermarkComparator = null;

    private DefaultPollContext(SourceCallback<T, A> sourceCallback, Serializable currentWatermark) {
      this.sourceCallback = sourceCallback;
      this.currentWatermark = currentWatermark;
    }

    @Override
    public PollItemStatus accept(Consumer<PollItem<T, A>> consumer) {
      final SourceCallbackContext callbackContext = sourceCallback.createContext();
      DefaultPollItem pollItem = new DefaultPollItem(callbackContext);

      consumer.accept(pollItem);

      pollItem.validate();

      PollItemStatus status;
      if (!acquireItem(pollItem, callbackContext)) {
        status = ALREADY_IN_PROCESS;
      } else if (!passesWatermark(pollItem)) {
        status = FILTERED_BY_WATERMARK;
      } else if (isRequestedToStop()) {
        status = SOURCE_STOPPING;
      } else {
        sourceCallback.handle(pollItem.getResult(), callbackContext);
        status = ACCEPTED;
      }

      if (status != ACCEPTED) {
        release(pollItem.getResult(), callbackContext);
      }

      return status;
    }

    @Override
    public boolean isSourceStopping() {
      return isRequestedToStop();
    }

    @Override
    public Optional<Serializable> getWatermark() {
      return ofNullable(currentWatermark);
    }

    @Override
    public void setWatermarkComparator(Comparator<? extends Serializable> comparator) {
      checkArgument(comparator != null, "Cannot set a null watermark comparator");
      this.watermarkComparator = (Comparator<Serializable>) comparator;
    }

    @Override
    public void onConnectionException(ConnectionException e) {
      sourceCallback.onConnectionException(e);
    }

    private Optional<Serializable> getUpdatedWatermark() {
      return ofNullable(updatedWatermark);
    }

    private Comparator<Serializable> getWatermarkComparator() {
      return watermarkComparator;
    }

    private boolean passesWatermark(DefaultPollItem pollItem) {
      Serializable itemWatermark = pollItem.getWatermark().orElse(null);
      if (itemWatermark == null) {
        return true;
      }
      String itemId = pollItem.getItemId().orElse(null);

      boolean accept = true;
      int compare;
      if (currentWatermark == null && updatedWatermark == null) {
        updatedWatermark = itemWatermark;
      } else {
        Serializable actingWatermark = currentWatermark != null ? currentWatermark : updatedWatermark;
        compare = compareWatermarks(actingWatermark, itemWatermark, watermarkComparator);
        if (compare < 0) {
          updatedWatermark = itemWatermark;
        } else if (compare == 0 && pollItem.getItemId().isPresent()) {
          try {
            accept = !recentlyProcessedIds.contains(itemId);
          } catch (ObjectStoreException e) {
            throw new RuntimeException(e);
          }
        } else {
          accept = false;
        }
      }

      if (accept) {
        try {
          if (itemId != null && !recentlyProcessedIds.contains(itemId)) {
            recentlyProcessedIds.store(itemId, itemId);
          }
        } catch (ObjectStoreException e) {
          throw new RuntimeException(e);
        }
      } else {
        if (LOGGER.isDebugEnabled()) {
          itemId = pollItem.getItemId().orElseGet(() -> pollItem.getResult().getAttributes().map(Object::toString).orElse(""));
          LOGGER.debug("Source at flow is skipping item '{}' because it was rejected by the watermark", flowName, itemId);
        }
      }

      return accept;
    }
  }

  private class DefaultPollItem implements PollItem<T, A> {

    private final SourceCallbackContext sourceCallbackContext;
    private Result<T, A> result;
    private Serializable watermark;
    private String itemId;

    private DefaultPollItem(SourceCallbackContext sourceCallbackContext) {
      this.sourceCallbackContext = sourceCallbackContext;
    }

    @Override
    public SourceCallbackContext getSourceCallbackContext() {
      return sourceCallbackContext;
    }

    @Override
    public PollItem<T, A> setResult(Result<T, A> result) {
      checkArgument(result != null, "Cannot set a null Result");
      this.result = result;

      return this;
    }

    @Override
    public PollItem<T, A> setWatermark(Serializable watermark) {
      checkArgument(watermark != null, "Cannot set a null watermark");
      this.watermark = watermark;

      return this;
    }

    @Override
    public PollItem<T, A> setId(String id) {
      checkArgument(id != null, "Cannot set a null id");
      itemId = id;

      return this;
    }

    private Optional<Serializable> getWatermark() {
      return ofNullable(watermark);
    }

    private Optional<String> getItemId() {
      return ofNullable(itemId);
    }

    private Result<T, A> getResult() {
      return result;
    }

    private void validate() {
      if (result == null) {
        throw new IllegalStateException(format(
                                               "Source at location '%s' pushed an item with a null Result", flowName));
      }
    }
  }

  private void release(Result<T, A> result, SourceCallbackContext context) {
    try {
      delegate.releaseRejectedResource(result, context);
    } finally {
      release(context);
    }
  }

  private void release(SourceCallbackContext context) {
    context.<ItemReleaser>getVariable(ITEM_RELEASER_CTX_VAR).ifPresent(ItemReleaser::release);
  }

  private void withWatermarkLock(CheckedRunnable runnable) {
    Lock lock = getWatermarkLock();
    lock.lock();
    try {
      runnable.run();
    } finally {
      lock.unlock();
    }
  }

  private Lock getWatermarkLock() {
    return lockFactory.createLock(formatKey("watermark"));
  }

  private void updateWatermark(Serializable value, Comparator comparator) {
    try {
      if (watermarkObjectStore.contains(WATERMARK_OS_KEY)) {
        Serializable currentValue = watermarkObjectStore.retrieve(WATERMARK_OS_KEY);
        if (compareWatermarks(currentValue, value, comparator) >= 0) {
          return;
        }
        watermarkObjectStore.remove(WATERMARK_OS_KEY);
        recentlyProcessedIds.clear();
      }

      watermarkObjectStore.store(WATERMARK_OS_KEY, value);
    } catch (ObjectStoreException e) {
      throw new MuleRuntimeException(
                                     createStaticMessage(format("Failed to update watermark value for message source at location '%s'. %s",
                                                                flowName, e.getMessage())),
                                     e);
    }
  }

  private Serializable getCurrentWatermark() {
    try {
      if (watermarkObjectStore.contains(WATERMARK_OS_KEY)) {
        return watermarkObjectStore.retrieve(WATERMARK_OS_KEY);
      } else {
        return null;
      }
    } catch (ObjectStoreException e) {
      throw new MuleRuntimeException(
                                     createStaticMessage(format("Failed to fetch watermark for Message source at location '%s'. %s",
                                                                flowName, e.getMessage())),
                                     e);
    }
  }

  private boolean acquireItem(DefaultPollItem pollItem, SourceCallbackContext callbackContext) {
    if (!pollItem.getItemId().isPresent()) {
      return true;
    }

    String id = pollItem.getItemId().get();
    Lock lock = lockFactory.createLock(flowName + "/" + id);
    if (!lock.tryLock()) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Source at flow '{}' is skipping processing of item '{}' because another thread or node already has a mule "
            + "lock on it", flowName, id);
      }
      return false;
    }

    try {
      if (inflightIdsObjectStore.contains(id)) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Source at flow '{}' polled item '{}', but skipping it since it is already being processed in another "
              + "thread or node", flowName, id);
        }
        lock.unlock();
        return false;
      } else {
        try {
          inflightIdsObjectStore.store(id, id);
          callbackContext.addVariable(ITEM_RELEASER_CTX_VAR, new ItemReleaser(id, lock));
          return true;
        } catch (ObjectStoreException e) {
          lock.unlock();
          LOGGER.error(format("Flow at source '%s' could not track item '%s' as being processed. %s",
                              flowName, id, e.getMessage()),
                       e);
          return false;
        }
      }
    } catch (Exception e) {
      lock.unlock();
      LOGGER.error(format("Could not guarantee idempotency for item '%s' for source at flow '%s'. '%s",
                          id, flowName, e.getMessage()),
                   e);
      return false;
    }
  }

  private boolean isRequestedToStop() {
    return stopRequested.get() || Thread.currentThread().isInterrupted();
  }

  private void shutdownScheduler() {
    if (executor != null) {
      executor.stop();
    }
  }

  private class ItemReleaser {

    private final String id;
    private final Lock lock;

    private ItemReleaser(String id, Lock lock) {
      this.id = id;
      this.lock = lock;
    }

    private void release() {
      try {
        if (inflightIdsObjectStore.contains(id)) {
          inflightIdsObjectStore.remove(id);
        }
      } catch (ObjectStoreException e) {
        LOGGER.error(format("Could not untrack item '%s' in source at flow '%s'. %s", id, flowName, e.getMessage()), e);
      } finally {
        lock.unlock();
      }
    }
  }
}
