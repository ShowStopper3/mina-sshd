/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.common.channel;

import java.io.IOException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Window for a given channel.
 * Windows are used to not overflow the client or server when sending datas.
 * Both clients and servers have a local and remote window and won't send
 * anymore data until the window has been expanded.  When the local window
 * is 
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class Window {

    private final static Logger log = LoggerFactory.getLogger(Window.class);

    private final AbstractChannel channel;
    private final Object lock;
    private final String name;

    private int size;
    private int maxSize;
    private int packetSize;
    private boolean waiting;
    private boolean closed;
    
    private final AtomicInteger sizeHolder = new AtomicInteger(0);
	private final AtomicBoolean initialized = new AtomicBoolean(false);
	
	 public static final Predicate<Window> SPACE_AVAILABLE_PREDICATE = new Predicate<Window>() {
	        @SuppressWarnings("synthetic-access")
	        @Override
	        public boolean evaluate(Window input) {
	            // NOTE: we do not call "getSize()" on purpose in order to avoid the lock
	            return input.sizeHolder.get() > 0;
	        }
	    };

    public Window(AbstractChannel channel, Object lock, boolean client, boolean local) {
        this.channel = channel;
        this.lock = lock != null ? lock : this;
        this.name = (client ? "client" : "server") + " " + (local ? "local " : "remote") + " window";
    }

    public int getSize() {
        synchronized (lock) {
            return size;
        }
    }

    public int getMaxSize() {
        return maxSize;
    }

    public int getPacketSize() {
        return packetSize;
    }

    public void init(int size, int packetSize) {
        synchronized (lock) {
            this.size = size;
            this.maxSize = size;
            this.packetSize = packetSize;
			updateSize(size);
		}
		if (initialized.getAndSet(true)) {
			log.debug("init({}) re-initializing", this);
		}
        
    }

    public void expand(int window) {
    	checkTrue(window >= 0, "Negative window size: %d", window);
		checkInitialized("expand");
		long expandedSize;
		synchronized (lock) {
			/*
			 * See RFC-4254 section 5.2:
			 *
			 * "Implementations MUST correctly handle window sizes of up to 2^32 - 1 bytes.
			 * The window MUST NOT be increased above 2^32 - 1 bytes.
			 */
			expandedSize = sizeHolder.get() + window;
			if (expandedSize > Integer.MAX_VALUE) {
				updateSize(Integer.MAX_VALUE);
			} else {
				updateSize((int) expandedSize);
			}
		}

		if (expandedSize > Integer.MAX_VALUE) {
		} else if (log.isDebugEnabled()) {
            log.debug("Increase {} by {} up to {}",  window, expandedSize);

		}
    }

    public void consume(int len) {
		checkTrue(len >= 0, "Negative consumption length: %d", len);
		checkInitialized("consume");
		int remainLen;
		synchronized (lock) {
			remainLen = sizeHolder.get() - len;
			if (remainLen >= 0) {
				updateSize(remainLen);
			}
		}
		if (remainLen < 0) {
			throw new IllegalStateException(
					"consume(" + this + ") required length (" + len + ") above available: " + (remainLen + len));
		}
    }

   protected void checkInitialized(String location) {
		if (!initialized.get()) {
			throw new IllegalStateException(location + " - window not initialized: " + this);
		}
	}

	protected void updateSize(int size) {
		checkTrue(size >= 0, "Invalid size: %d", size);
		this.sizeHolder.set(size);
		lock.notifyAll();
	}

    public void consumeAndCheck(int len) throws IOException {
        synchronized (lock) {
            //assert size > len;
            size -= len;
            if (log.isTraceEnabled()) {
                log.trace("Consume " + name + " by " + len + " down to " + size);
            }
            check(maxSize);
        }
    }

    public void check(int maxFree) throws IOException {
        synchronized (lock) {
            if (size < maxFree / 2) {
                if (log.isDebugEnabled()) {
                    log.debug("Increase " + name + " by " + (maxFree - size) + " up to " + maxFree);
                }
                channel.sendWindowAdjust(maxFree - size);
                size = maxFree;
            }
        }
    }

    public void waitAndConsume(final int len, long maxWaitTime) throws InterruptedException, WindowClosedException, SocketTimeoutException {
        checkTrue(len >= 0, "Negative wait consume length: %d", len);
        checkInitialized("waitAndConsume");
        synchronized (lock) {
            waitForCondition(new Predicate<Window>() {
                @SuppressWarnings("synthetic-access")
                @Override
                public boolean evaluate(Window input) {
                    // NOTE: we do not call "getSize()" on purpose in order to avoid the lock
                    return input.sizeHolder.get() >= len;
                }
            }, maxWaitTime);

            if (log.isDebugEnabled()) {
                log.debug("waitAndConsume({}) - requested={}, available={}",  len, sizeHolder);
            }
            consume(len);
        }
    
	}

	public int waitForSpace(long maxWaitTime)
			throws InterruptedException, WindowClosedException, SocketTimeoutException {
        checkInitialized("waitForSpace");
        synchronized (lock) {
            waitForCondition(SPACE_AVAILABLE_PREDICATE, maxWaitTime);
            if (log.isDebugEnabled()) {
                log.debug("waitForSpace({}) available: {}", this, sizeHolder);
            }
            return sizeHolder.get();
        }
    
	}

    public void notifyClosed() {
        synchronized (lock) {
            closed = true;
            if (waiting) {
                lock.notifyAll();
            }
        }
    }
    
	protected void waitForCondition(Predicate<? super Window> predicate, long maxWaitTime)
			throws WindowClosedException, InterruptedException, SocketTimeoutException {
		checkNotNull(predicate, "No condition");
		checkTrue(maxWaitTime > 0, "Non-positive max. wait time: %d", maxWaitTime);

		long maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(maxWaitTime);
		long remWaitNanos = maxWaitNanos;
		// The loop takes care of spurious wakeups
		while (isOpen() && (remWaitNanos > 0L)) {
			if (predicate.evaluate(this)) {
				return;
			}

			long curWaitMillis = TimeUnit.NANOSECONDS.toMillis(remWaitNanos);
			long nanoWaitStart = System.nanoTime();
			if (curWaitMillis > 0L) {
				lock.wait(curWaitMillis);
			} else { // only nanoseconds remaining
				lock.wait(0L, (int) remWaitNanos);
			}
			long nanoWaitEnd = System.nanoTime();
			long nanoWaitDuration = nanoWaitEnd - nanoWaitStart;
			remWaitNanos -= nanoWaitDuration;
		}

		if (!isOpen()) {
			throw new WindowClosedException();
		}

		throw new SocketTimeoutException("waitForCondition(" + this + ") timeout exceeded: " + maxWaitTime);
	}

	public boolean isOpen() {
		return !closed;
	}

	public static <T> T checkNotNull(T t, String message) {
		checkTrue(t != null, message);
		return t;
	}

	public static void checkTrue(boolean flag, String message) {
		if (!flag) {
			throw new IllegalArgumentException(message);
		}
	}

	public static void checkTrue(boolean flag, String message, long value) {
		if (!flag) {
			log.debug("Inside check true with value" + value);
			throw new IllegalArgumentException(message);
		}
	}
}
