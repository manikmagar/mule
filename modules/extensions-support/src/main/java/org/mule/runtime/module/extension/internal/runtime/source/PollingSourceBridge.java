/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.core.api.source.scheduler.Scheduler;
import org.mule.runtime.extension.api.runtime.source.PollingSource;
import org.mule.runtime.extension.api.runtime.source.Source;
import org.mule.runtime.extension.api.runtime.source.SourceCallback;

public class PollingSourceBridge<T, A> extends Source<T, A> {

  private final PollingSource<T, A> delegate;
  //TODO: To be added via enricher
  private final Scheduler schedulingStrategy;

  public PollingSourceBridge(PollingSource<T, A> delegate, Scheduler schedulingStrategy) {
    this.delegate = delegate;
    this.schedulingStrategy = schedulingStrategy;
  }

  @Override
  public void onStart(SourceCallback<T, A> sourceCallback) throws MuleException {

  }

  @Override
  public void onStop() {

  }
}
