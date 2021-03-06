/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.test.extension.reconnection;

import static org.mule.extension.test.extension.reconnection.ReconnectableConnectionProvider.fail;


/**
 * This class is a container for operations, every public method in this class will be taken as an extension operation.
 */
public class ReconnectionOperations {

  /**
   * Example of a simple operation that receives a string parameter and returns a new string message that will be set on the payload.
   */
  public void switchConnection() {
    fail = !fail;
  }
}
