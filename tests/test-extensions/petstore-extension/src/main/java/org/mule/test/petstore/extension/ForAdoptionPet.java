/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.petstore.extension;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.source.PollingSource;
import org.mule.runtime.extension.api.runtime.source.WatermarkHandler;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class ForAdoptionPet extends PollingSource<String, Void> {

  private List<String> pets;
  private List<String> rejectedPets = new LinkedList<>();

  @Parameter
  @org.mule.runtime.extension.api.annotation.param.Optional(defaultValue = "false")
  private boolean watermark;

  @Override
  protected void doStart() throws MuleException {
    pets = asList("Grumpy Cat", "Colonel Meow", "Silvester", "Lil bub", "Macri", "Pappo");
  }

  @Override
  protected void doStop() {
    pets.clear();
  }

  @Override
  public List<Result<String, Void>> poll() {
    return pets.stream().map(p -> Result.<String, Void>builder().output(p).build()).collect(toList());
  }

  @Override
  public void releaseRejectedResource(Result<String, Void> result) {
    rejectedPets.add(result.getOutput());
  }

  @Override
  public Optional<Function<Result<String, Void>, String>> getIdentityResolver() {
    return Optional.of(r -> r.getOutput());
  }

  @Override
  public Optional<WatermarkHandler<String, Void, Integer>> getWatermarkHandler() {
    return watermark ? Optional.of(new Watermark()) : Optional.empty();
  }

  private class Watermark implements WatermarkHandler<String, Void, Integer> {

    @Override
    public Integer getWatermark(Result<String, Void> result) {
      return pets.indexOf(result.getOutput());
    }

    @Override
    public Comparator<Integer> getComparator() {
      return Integer::compareTo;
    }

    @Override
    public Integer getInitialWatermark() {
      return -1;
    }
  }
}
