/*
 *  Copyright 2006-2010 Brian S O'Neill
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

import java.io.InputStream;
import java.io.IOException;

import java.util.LinkedList;
import java.util.Queue;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;

/**
 * Unbuffered replacement for {@link java.io.PipedInputStream}. This piped
 * stream does not have the flaws found in the java.io implementation. It
 * allows multiple threads to read from it without interfering with the
 * stream's state. Also it can't get into a one-second polling mode.
 *
 * @author Brian S O'Neill
 * @see PipedOutputStream
 */
public class PipedInputStream extends InputStream {
    private static final int NOT_CONNECTED = 0, CONNECTED = 1, HALF_CLOSED = 2, CLOSED = 3;

    private final Lock mLock;

    private PipedOutputStream mPout;
    private int mConnectState;

    private Queue<Channel.Listener> mListenerQueue;

    public PipedInputStream() {
        mLock = new ReentrantLock();
    }

    public PipedInputStream(PipedOutputStream pout) throws IOException {
        mLock = pout.setInput(this);
        setOutput(pout);
    }

    public int read() throws IOException {
        mLock.lock();
        try {
            return mPout.read();
        } catch (Exception e) {
            checkHalfClosed(e);
            return -1;
        } finally {
            mLock.unlock();
        }
    }

    public int read(byte[] bytes) throws IOException {
        return read(bytes, 0, bytes.length);
    }

    public int read(byte[] bytes, int offset, int length) throws IOException {
        mLock.lock();
        try {
            return mPout.read(bytes, offset, length);
        } catch (Exception e) {
            checkHalfClosed(e);
            return -1;
        } finally {
            mLock.unlock();
        }
    }

    public long skip(long n) throws IOException {
        mLock.lock();
        try {
            return mPout.skip(n);
        } catch (Exception e) {
            checkHalfClosed(e);
            return 0;
        } finally {
            mLock.unlock();
        }
    }

    public int available() throws IOException {
        mLock.lock();
        try {
            return mPout.inputAvailable();
        } catch (Exception e) {
            checkHalfClosed(e);
            return 0;
        } finally {
            mLock.unlock();
        }
    }

    public boolean isReady() throws IOException {
        return available() > 0;
    }

    public boolean isClosed() {
        mLock.lock();
        try {
            return mConnectState == CLOSED;
        } finally {
            mLock.unlock();
        }
    }

    public void close() {
        mLock.lock();
        try {
            if (mPout != null) {
                PipedOutputStream pout = mPout;
                mPout = null;
                pout.close();
            }
            mConnectState = CLOSED;
        } finally {
            mLock.unlock();
        }
    }

    @Override
    public String toString() {
        String superStr = superToString();

        mLock.lock();
        try {
            if (mPout == null) {
                return superStr.concat(" (unconnected)");
            } else {
                return superStr + " connected to " + mPout.superToString();
            }
        } finally {
            mLock.unlock();
        }
    }

    void inputNotify(final IOExecutor executor, final Channel.Listener listener) {
        mLock.lock();
        try {
            try {
                int avail;
                if (isReady()) {
                    new PipeNotify(executor, listener);
                    return;
                }
            } catch (IOException e) {
                new PipeNotify(executor, listener, e);
                return;
            }

            Queue<Channel.Listener> queue = mListenerQueue;
            if (queue == null) {
                mListenerQueue = queue = new LinkedList<Channel.Listener>();
            }

            queue.add(new Channel.Listener() {
                public void ready() {
                    new PipeNotify(executor, listener);
                }

                public void rejected(RejectedException cause) {
                    listener.rejected(cause);
                }

                public void closed(IOException cause) {
                    new PipeNotify(executor, listener, cause);
                }
            });
        } finally {
            mLock.unlock();
        }
    }

    // Caller must hold mLock.
    void notifyReady() {
        Queue<Channel.Listener> queue = mListenerQueue;
        if (queue != null) {
            Channel.Listener listener = queue.poll();
            if (listener != null) {
                listener.ready();
            }
        }
    }

    // Caller must hold mLock.
    void notifyClosed() {
        Queue<Channel.Listener> queue = mListenerQueue;
        if (queue != null) {
            ClosedException ex = new ClosedException();
            Channel.Listener listener;
            while ((listener = queue.poll()) != null) {
                listener.closed(ex);
            }
        }
    }

    void outputClosed() {
        mLock.lock();
        try {
            mPout = null;
            if (mConnectState != CLOSED) {
                mConnectState = HALF_CLOSED;
            }
        } finally {
            mLock.unlock();
        }
    }

    String superToString() {
        return super.toString();
    }

    Lock setOutput(PipedOutputStream pout) throws IOException {
        mLock.lock();
        try {
            switch (mConnectState) {
            case NOT_CONNECTED:
                mPout = pout;
                mConnectState = CONNECTED;
                return mLock;
            case CONNECTED:
                throw new IOException("Already connected");
            default:
                throw new ClosedException();
            }
        } finally {
            mLock.unlock();
        }
    }

    // Caller must hold mLock.
    private void checkHalfClosed(Exception e) throws IOException {
        if (mPout == null) {
            if (mConnectState == HALF_CLOSED) {
                return;
            }

            if (e instanceof NullPointerException) {
                if (mConnectState == NOT_CONNECTED) {
                    e = new IOException("Not connected");
                } else {
                    e = new ClosedException();
                }
            }
        }

        if (e instanceof IOException) {
            throw (IOException) e;
        }

        throw new IOException(e);
    }
}
