/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source;

import static reactor.core.publisher.Mono.defer;
import static reactor.core.publisher.Mono.from;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.extension.api.runtime.source.SourceCallbackContext;

import java.util.Map;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

public class CompositeSourceCallbackExecutor implements SourceCallbackExecutor {

  private final SourceCallbackExecutor before;
  private final SourceCallbackExecutor delegate;
  private final SourceCallbackExecutor after;

  public CompositeSourceCallbackExecutor(SourceCallbackExecutor before,
                                         SourceCallbackExecutor delegate,
                                         SourceCallbackExecutor after) {
    this.before = before;
    this.delegate = delegate;
    this.after = after;
  }

  @Override
  public Publisher<Void> execute(CoreEvent event, Map<String, Object> parameters, SourceCallbackContext context) {
    Mono<Void> mono;
    if (before != null) {
      mono = defer(() -> from(before.execute(event, parameters, context))
          .transform(v -> defer(() -> from(delegate.execute(event, parameters, context)))));
    } else {
      mono = defer(() -> from(delegate.execute(event, parameters, context)));
    }

    if (after != null) {
      mono = mono.transform(v -> defer(() -> from(after.execute(event, parameters, context))));
    }

    return mono;
  }
}
