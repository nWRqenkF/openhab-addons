/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.ipcamera.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ipcamera.internal.handler.IpCameraHandler;
import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link IpCameraActions} is responsible for Actions.
 *
 * @author Matthew Skinner - Initial contribution
 */

@ThingActionsScope(name = "ipcamera")
@NonNullByDefault
public class IpCameraActions implements ThingActions {
    public final Logger logger = LoggerFactory.getLogger(getClass());
    private @Nullable IpCameraHandler handler;

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        this.handler = (IpCameraHandler) handler;
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    @RuleAction(label = "Record MP4", description = "Record MP4 to a set filename if given, or if filename is null to ipcamera.mp4")
    public void recordMP4(
            @ActionInput(name = "filename", label = "Filename", description = "Name that the recording will have once created, don't include the .mp4.") @Nullable String filename,
            @ActionInput(name = "secondsToRecord", label = "Seconds to Record", description = "Enter a number of how many seconds to record.") int secondsToRecord) {
        logger.debug("Recording {}.mp4 for {} seconds.", filename, secondsToRecord);
        if (filename == null && handler != null) {
            handler.recordMp4("ipcamera", secondsToRecord);
        } else if (handler != null && filename != null) {
            handler.recordMp4(filename, secondsToRecord);
        }
    }

    public static void recordMP4(@Nullable ThingActions actions, @Nullable String filename, int secondsToRecord) {
        if (actions instanceof IpCameraActions) {
            ((IpCameraActions) actions).recordMP4(filename, secondsToRecord);
        } else {
            throw new IllegalArgumentException("Instance is not a IpCamera class.");
        }
    }

    @RuleAction(label = "Record GIF", description = "Record GIF to a set filename if given, or if filename is null to ipcamera.gif")
    public void recordGIF(
            @ActionInput(name = "filename", label = "Filename", description = "Name that the recording will have once created, don't include the .mp4.") @Nullable String filename,
            @ActionInput(name = "secondsToRecord", label = "Seconds to Record", description = "Enter a number of how many seconds to record.") int secondsToRecord) {
        logger.debug("Recording {}.gif for {} seconds.", filename, secondsToRecord);
        if (filename == null && handler != null) {
            handler.recordGif("ipcamera", secondsToRecord);
        } else if (handler != null && filename != null) {
            handler.recordGif(filename, secondsToRecord);
        }
    }

    public static void recordGIF(@Nullable ThingActions actions, @Nullable String filename, int secondsToRecord) {
        if (actions instanceof IpCameraActions) {
            ((IpCameraActions) actions).recordGIF(filename, secondsToRecord);
        } else {
            throw new IllegalArgumentException("Instance is not a IpCamera class.");
        }
    }
}
