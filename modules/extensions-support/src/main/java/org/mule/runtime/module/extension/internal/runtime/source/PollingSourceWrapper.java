/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source;

import static java.lang.String.format;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.store.ObjectStoreSettings.unmanagedPersistent;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Mono.fromRunnable;
import org.mule.runtime.api.component.location.ComponentLocation;
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
import org.mule.runtime.core.api.source.scheduler.FixedFrequencyScheduler;
import org.mule.runtime.core.api.source.scheduler.Scheduler;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.source.PollingSource;
import org.mule.runtime.extension.api.runtime.source.SourceCallback;
import org.mule.runtime.extension.api.runtime.source.SourceCallbackContext;
import org.mule.runtime.extension.api.runtime.source.WatermarkHandler;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import javax.inject.Inject;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;

public class PollingSourceWrapper<T, A> extends SourceWrapper<T, A> {

  private static final Logger LOGGER = getLogger(PollingSourceWrapper.class);
  private static final String ITEM_RELEASER_CTX_VAR = "itemReleaser";
  private static final String WATERMARK_OS_KEY = "watermark";

  private final PollingSource<T, A> delegate;
  //TODO: To be added via enricher
  private final Scheduler scheduler;

  @Inject
  private LockFactory lockFactory;

  @Inject
  private ObjectStoreManager objectStoreManager;

  @Inject
  private SchedulerService schedulerService;

  private ObjectStore<Serializable> watermarkObjectStore;
  private ObjectStore<Serializable> inflightIdsObjectStore;
  private Function<Result<T, A>, String> identityResolver;
  private WatermarkHandler<T, A, Serializable> watermarkHandler;

  private ComponentLocation componentLocation;
  private String location;
  private final AtomicBoolean stopRequested = new AtomicBoolean(false);
  private org.mule.runtime.api.scheduler.Scheduler executor;

  public PollingSourceWrapper(PollingSource<T, A> delegate, Scheduler scheduler) {
    super(delegate);
    this.delegate = delegate;
    //this.scheduler = scheduler;
    this.scheduler = new FixedFrequencyScheduler();
  }

  @Override
  public void onStart(SourceCallback<T, A> sourceCallback) throws MuleException {
    delegate.onStart(sourceCallback);
    location = componentLocation.getLocation();
    identityResolver = delegate.getIdentityResolver().orElse(null);
    if (identityResolver != null) {
      inflightIdsObjectStore = objectStoreManager.getOrCreateObjectStore(location + "/inflight-ids",
                                                                         ObjectStoreSettings.builder()
                                                                             .persistent(false)
                                                                             .maxEntries(1000)
                                                                             .entryTtl(60000L)
                                                                             .expirationInterval(20000L)
                                                                             .build());

    }

    watermarkHandler = delegate.getWatermarkHandler().orElse(null);
    if (watermarkHandler != null) {
      watermarkObjectStore = objectStoreManager.getOrCreateObjectStore(location + "/watermark",
                                                                       unmanagedPersistent());
    }

    executor = schedulerService.customScheduler(SchedulerConfig.config()
                                                    .withMaxConcurrentTasks(1)
                                                    .withName(location + ":executor"));

    stopRequested.set(false);
    scheduler.schedule(executor, () -> poll(sourceCallback));
  }

  @Override
  public void onStop() {
    stopRequested.set(true);
    shutdownScheduler();
    try {
      delegate.onStop();
    } catch (Throwable t) {

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
    return fromRunnable(() -> context.<ItemReleaser>getVariable(ITEM_RELEASER_CTX_VAR).ifPresent(ItemReleaser::release));
  }

  private void poll(SourceCallback<T, A> sourceCallback) {
    if (isRequestedToStop()) {
      return;
    }

    try {
      List<Result<T, A>> results = delegate.poll();
      if (results == null || results.isEmpty()) {
        return;
      }

      results = filterWatermark(results);

      for (Result<T, A> result : results) {
        SourceCallbackContext callbackContext = sourceCallback.createContext();

        String itemId = acquireItem(result, callbackContext);
        if (itemId == null) {
          delegate.releaseRejectedResource(result);
          continue;
        }

        try {
          delegate.onResult(result, callbackContext);
          sourceCallback.handle(result, callbackContext);
        } catch (Exception e) {
          delegate.releaseRejectedResource(result);
          //handle
        }
      }
    } catch (Exception e) {
      // handle
    }
  }

  private List<Result<T, A>> filterWatermark(List<Result<T, A>> results) {
    if (watermarkHandler == null) {
      return results;
    }

    Lock lock = lockFactory.createLock(location + "/watermark");
    lock.lock();

    try {
      final Serializable previousWatermark = getWatermark();
      final Comparator<Serializable> comparator = watermarkHandler.getComparator();
      final List<Result<T, A>> passed = new ArrayList<>(results.size());
      Serializable updatedWatermark = null;

      for (Result<T, A> result : results) {
        final Serializable itemWatermark = watermarkHandler.getWatermark(result);
        if (comparator.compare(previousWatermark, itemWatermark) >= 0) {
          if (LOGGER.isDebugEnabled()) {
            String itemId = identityResolver != null
                ? identityResolver.apply(result)
                : result.getAttributes().map(Object::toString).orElse("");

            LOGGER.debug("Skipping item '{}' because it was rejected by the watermark", itemId);
          }
        } else {
          passed.add(result);
          if (updatedWatermark == null) {
            updatedWatermark = itemWatermark;
          } else if (comparator.compare(itemWatermark, updatedWatermark) > 0) {
            updatedWatermark = itemWatermark;
          }
        }
      }

      updateWatermark(updatedWatermark);
      return passed;
    } finally {
      lock.unlock();
    }
  }

  private void updateWatermark(Serializable value) {
    if (value == null) {
      return;
    }

    try {
      if (watermarkObjectStore.contains(WATERMARK_OS_KEY)) {
        watermarkObjectStore.remove(WATERMARK_OS_KEY);
      }

      watermarkObjectStore.store(WATERMARK_OS_KEY, value);
    } catch (ObjectStoreException e) {
      throw new MuleRuntimeException(
          createStaticMessage(format("Failed to update watermark value for message source at location '%s'. %s",
                                     location, e.getMessage())), e);
    }
  }

  private Serializable getWatermark() {
    try {
      if (watermarkObjectStore.contains(WATERMARK_OS_KEY)) {
        return watermarkObjectStore.retrieve(WATERMARK_OS_KEY);
      } else {
        return watermarkHandler.getInitialWatermark();
      }
    } catch (ObjectStoreException e) {
      throw new MuleRuntimeException(
          createStaticMessage(format("Failed to fetch watermark for Message source at location '%s'. %s",
                                     location, e.getMessage())), e);
    }
  }

  private String acquireItem(Result<T, A> result, SourceCallbackContext callbackContext) {
    if (identityResolver == null) {
      return null;
    }

    String id;
    try {
      id = identityResolver.apply(result);
      if (id == null) {
        // throw new RuntimeException("?");
        return null;
      }
    } catch (Throwable t) {
      //handle
      return null;
    }

    Lock lock = lockFactory.createLock(location + "/" + id);
    if (!lock.tryLock()) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Skipping processing of item '{}' because another thread or node already has a mule lock on it", id);
      }
      return null;
    }

    try {

      if (inflightIdsObjectStore.contains(id)) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Polled item '{}', but skipping it since it is already being processed in another thread or node", id);
        }
        lock.unlock();
        return null;
      } else {
        try {
          inflightIdsObjectStore.store(id, id);
          callbackContext.addVariable(ITEM_RELEASER_CTX_VAR, new ItemReleaser(id, lock));
          return id;
        } catch (ObjectStoreException e) {
          lock.unlock();
          LOGGER.error(format("Could not track item '%s' as being processed. %s", id, e.getMessage()), e);
          return null;
        }
      }
    } catch (Exception e) {
      lock.unlock();
      // handle;
      return null;
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
        // handle
      }
      finally {
        lock.unlock();
      }
    }
  }
}
