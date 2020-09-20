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
package org.openhab.binding.upb.internal.handler;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.upb.internal.handler.UPBIoHandler.CmdStatus;
import org.openhab.binding.upb.internal.message.MessageBuilder;
import org.openhab.binding.upb.internal.message.MessageParseException;
import org.openhab.binding.upb.internal.message.UPBMessage;
import org.openhab.core.common.NamedThreadFactory;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.util.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event loop for serial communications. Handles sending and receiving UPB messages.
 *
 * @author Marcus Better - Initial contribution
 *
 */
@NonNullByDefault
public class SerialIoThread extends Thread {
    private static final int WRITE_QUEUE_LENGTH = 128;
    private static final int ACK_TIMEOUT_MS = 500;
    private static final byte[] ENABLE_MESSAGE_MODE_CMD = "\u001770028E\n".getBytes(StandardCharsets.US_ASCII);

    private static final int MAX_READ_SIZE = 128;
    private static final int CR = 13;

    private final Logger logger = LoggerFactory.getLogger(SerialIoThread.class);
    private final MessageListener listener;
    // Single-threaded executor for writes that serves to serialize writes.
    private final ExecutorService writeExecutor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(WRITE_QUEUE_LENGTH), new NamedThreadFactory("upb-serial-writer", true));
    private final SerialPort serialPort;

    private volatile @Nullable WriteRunnable currentWrite;
    private volatile boolean done;

    public SerialIoThread(final SerialPort serialPort, final MessageListener listener, final ThingUID thingUID) {
        this.serialPort = serialPort;
        this.listener = listener;
        setName("OH-binding-" + thingUID + "-serial-reader");
        setDaemon(true);
    }

    @Override
    public void run() {
        enterMessageMode();
        try (final InputStream in = serialPort.getInputStream()) {
            if (in == null) {
                // should never happen
                throw new IllegalStateException("serial port is not readable");
            }
            try (final InputStream bufIn = new BufferedInputStream(in)) {
                bufIn.mark(MAX_READ_SIZE);
                int len = 0;
                while (!done) {
                    final int b = bufIn.read();
                    if (b == -1) {
                        // the serial input returns -1 on receive timeout
                        continue;
                    }
                    len++;
                    if (b == CR) {
                        // message terminator read, rewind the stream and parse the buffered message
                        try {
                            bufIn.reset();
                            processBuffer(bufIn, len);
                        } catch (final IOException e) {
                            logger.warn("buffer overrun, dropped long message", e);
                        } finally {
                            bufIn.mark(MAX_READ_SIZE);
                            len = 0;
                        }
                    }
                }
            }
        } catch (final IOException e) {
            logger.warn("Exception in UPB read thread", e);
        } finally {
            logger.debug("shutting down receive thread");
            shutdownAndAwaitTermination(writeExecutor);
            try {
                serialPort.close();
            } catch (final RuntimeException e) {
                // ignore
            }
        }
        logger.debug("UPB read thread stopped");
    }

    /**
     * Attempts to parse a message from the input stream.
     *
     * @param in the stream to read from
     * @param len the number of bytes in the message
     */
    private void processBuffer(final InputStream in, final int len) {
        final byte[] buf = new byte[len];
        final int n;
        try {
            n = in.read(buf);
        } catch (final IOException e) {
            logger.warn("error reading message", e);
            return;
        }
        if (n < len) {
            // should not happen when replaying the buffered input
            logger.warn("truncated read, expected={} read={}", len, n);
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("UPB Message: {}", HexUtils.bytesToHex(buf));
        }
        final UPBMessage msg;
        try {
            msg = UPBMessage.parse(buf);
        } catch (final MessageParseException e) {
            logger.warn("failed to parse message: {}", HexUtils.bytesToHex(buf), e);
            return;
        }
        handleMessage(msg);
    }

    private void handleMessage(final UPBMessage msg) {
        final WriteRunnable writeRunnable = currentWrite;
        switch (msg.getType()) {
            case ACK:
                if (writeRunnable != null) {
                    writeRunnable.ackReceived(true);
                }
                break;
            case NAK:
                if (writeRunnable != null) {
                    writeRunnable.ackReceived(false);
                }
                break;
            case ACCEPT:
                break;
            case ERROR:
                logger.debug("received ERROR response from PIM");
                break;
            default:
                // ignore
        }
        listener.incomingMessage(msg);
    }

    public CompletionStage<CmdStatus> enqueue(final MessageBuilder msg) {
        final CompletableFuture<CmdStatus> completion = new CompletableFuture<>();
        final Runnable task = new WriteRunnable(msg.build(), completion);
        try {
            writeExecutor.execute(task);
        } catch (final RejectedExecutionException e) {
            completion.completeExceptionally(e);
        }
        return completion;
    }

    // puts the PIM is in message mode
    private void enterMessageMode() {
        try {
            final OutputStream out = serialPort.getOutputStream();
            if (out == null) {
                throw new IOException("serial port is not writable");
            }
            out.write(ENABLE_MESSAGE_MODE_CMD);
            out.flush();
        } catch (final IOException e) {
            logger.warn("error setting message mode", e);
        }
    }

    void shutdownAndAwaitTermination(final ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                    logger.warn("executor did not terminate");
                }
            }
        } catch (final InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void terminate() {
        done = true;
        try {
            serialPort.close();
        } catch (final RuntimeException e) {
            logger.warn("failed to close serial port", e);
        }
    }

    private class WriteRunnable implements Runnable {
        private static final int MAX_RETRIES = 3;

        private final String msg;
        private final CompletableFuture<CmdStatus> completion;
        private final CountDownLatch ackLatch = new CountDownLatch(1);

        private @Nullable Boolean ack;

        public WriteRunnable(final String msg, final CompletableFuture<CmdStatus> completion) {
            this.msg = msg;
            this.completion = completion;
        }

        // called by reader thread on ACK or NAK
        public void ackReceived(final boolean ack) {
            if (logger.isDebugEnabled()) {
                if (ack) {
                    logger.debug("ACK received");
                } else {
                    logger.debug("NAK received");
                }
            }
            this.ack = ack;
            ackLatch.countDown();
        }

        @Override
        public void run() {
            currentWrite = this;
            try {
                logger.debug("Writing bytes: {}", msg);
                final OutputStream out = serialPort.getOutputStream();
                if (out == null) {
                    throw new IOException("serial port is not writable");
                }
                for (int tries = 0; tries < MAX_RETRIES && ack == null; tries++) {
                    out.write(0x14);
                    out.write(msg.getBytes(US_ASCII));
                    out.write(0x0d);
                    out.flush();
                    final boolean acked = ackLatch.await(ACK_TIMEOUT_MS, MILLISECONDS);
                    if (acked) {
                        break;
                    }
                    logger.debug("ack timed out, retrying ({} of {})", tries + 1, MAX_RETRIES);
                }
                final Boolean ack = this.ack;
                if (ack == null) {
                    logger.debug("write not acked");
                    completion.complete(CmdStatus.WRITE_FAILED);
                } else if (ack) {
                    completion.complete(CmdStatus.ACK);
                } else {
                    completion.complete(CmdStatus.NAK);
                }
            } catch (final IOException | InterruptedException e) {
                logger.warn("error writing message", e);
                completion.complete(CmdStatus.WRITE_FAILED);
            }
        }
    }
}
