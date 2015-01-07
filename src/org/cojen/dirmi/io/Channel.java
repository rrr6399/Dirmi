/*
 *  Copyright 2009-2010 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.io;

import java.io.Flushable;
import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.rmi.Remote;

import org.cojen.dirmi.Link;
import org.cojen.dirmi.RejectedException;

/**
 * Basic interface for a blocking bidirectional I/O channel.
 *
 * @author Brian S O'Neill
 */
public interface Channel extends Flushable, Closeable, Link {
    /**
     * Returns an input stream for the channel, which when closed, closes the
     * channel. When a channel is closed, all read operations throw
     * IOException.
     */
    InputStream getInputStream();

    /**
     * Returns an output stream for the channel, which when closed, closes the
     * channel. When a channel is closed, all write operations throw
     * IOException.
     */
    OutputStream getOutputStream();

    /**
     * Returns true if bytes can read from channel without blocking.
     *
     * @throws IOException if channel is closed
     */
    boolean isInputReady() throws IOException;

    /**
     * Returns true if bytes can written to channel without blocking.
     *
     * @throws IOException if channel is closed
     */
    boolean isOutputReady() throws IOException;

    /**
     * Sets the size of the input buffer, returning the actual size applied.
     */
    int setInputBufferSize(int size);

    /**
     * Sets the size of the output buffer, returning the actual size applied.
     */
    int setOutputBufferSize(int size);

    /**
     * Register a listener which is asynchronously notified when a call to
     * isInputReady will return true. Listener is called at most once per
     * registration.
     */
    void inputNotify(Listener listener);

    /**
     * Register a listener which is asynchronously notified when a call to
     * isOutputReady will return true. Listener is called at most once per
     * registration.
     */
    void outputNotify(Listener listener);

    /**
     * Returns true to indicate that asynchronous notifications use channel
     * selection instead of blocked threads. For channels that aren't
     * select-based, it can be more efficient to block on I/O streams directly
     * rather than wait for notifications.
     */
    boolean usesSelectNotification();

    /**
     * Allows input to resume after reading a suspend marker.
     *
     * @return false if not supported or if no suspend marker is present
     */
    boolean inputResume();

    boolean isResumeSupported();

    /**
     * If supported, writes a suspend marker and flushes the output. Input side
     * will read EOF until resumed. Input must be resumed for each suspend.
     *
     * @return false if not supported or stream is closed
     */
    boolean outputSuspend() throws IOException;

    /**
     * Register this channel with at least one group. When this channel is
     * closed, it removes itself from all groups it has been registered with.
     */
    void register(CloseableGroup<? super Channel> group);

    /**
     * Returns true if channel is absolutely closed or disconnected. A return
     * value of false doesn't imply that the next I/O operation will succeed.
     */
    boolean isClosed();

    /**
     * Close channel, flushing any remaining output first.
     */
    void close() throws IOException;

    /**
     * Forcibly close channel, potentially discarding unflushed output and any
     * exceptions.
     */
    void disconnect();

    // Note: I don't like the magic control object design, but it makes it much
    // easier to implement a reliable socket recycling scheme.

    /**
     * Install a recycler which is called after this channel has been closed
     * and a recyled channel has been created. Method returns an opaque remote
     * object which allows remote channel endpoint to control this
     * channel. Channels which don't use this feature return null.
     *
     * @throws IllegalStateException if a recycler is already installed
     */
    Remote installRecycler(Recycler recycler);

    /**
     * Set the recycling control object from the remote channel
     * endpoint. Method does nothing if channel doesn't use this feature.
     *
     * @throws IllegalArgumentException if channel uses control feature and
     * instance is same as local control or is unsupported
     */
    void setRecycleControl(Remote control);

    public static interface Listener {
        /**
         * Called when bytes can be read or written to channel, depending on
         * how listener was registered. This method is always invoked in a
         * separate thread from registration, and so it may safely block.
         */
        void ready();

        /**
         * Called if no threads are available to invoke any listeners, but does
         * not imply that channel is closed. This method is usually invoked by
         * a registration thread.
         */
        void rejected(RejectedException cause);

        /**
         * Called if listener notification failed because channel is closed.
         * This method may be invoked by a registration thread.
         */
        void closed(IOException cause);
    }

    public static interface Recycler {
        /**
         * Called with a newly recycled channel, which does not yet have a
         * recycler installed.
         */
        void recycled(Channel channel);
    }
}
