/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.api.processor;

/**
 * A {@link MessageProcessor} that routes messages to zero or more destination
 * message processors. Implementations determine exactly how this is done by making
 * decisions about which route(s) should be used and if the message should be copied
 * or not.
 */
public interface MessageRouter extends MessageProcessor
{
    /**
     * Adds a new message processor to the list of routes
     * 
     * @param processor new destination message processor
     */
    void addRoute(MessageProcessor processor);

    /**
     * Removes a message processor from the list of routes
     * 
     * @param processor destination message processor to remove
     * @return true if the route was removed
     */
    boolean removeRoute(MessageProcessor processor);

}
