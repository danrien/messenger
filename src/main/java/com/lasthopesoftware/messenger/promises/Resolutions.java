package com.lasthopesoftware.messenger.promises;

import com.lasthopesoftware.messenger.SingleMessageBroadcaster;
import com.lasthopesoftware.messenger.promises.aggregation.AggregateCancellation;
import com.lasthopesoftware.messenger.promises.aggregation.CollectedErrorExcuse;
import com.lasthopesoftware.messenger.promises.aggregation.CollectedResultsResolver;
import com.lasthopesoftware.messenger.promises.propagation.ResolutionProxy;
import com.lasthopesoftware.messenger.promises.response.ImmediateResponse;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class Resolutions {

	static final class AggregatePromiseResolver<Resolution> extends SingleMessageBroadcaster<Collection<Resolution>> {

		AggregatePromiseResolver(Collection<Promise<Resolution>> promises) {
			if (promises.isEmpty()) {
				sendResolution(Collections.<Resolution>emptyList());
				return;
			}

			final CollectedErrorExcuse<Resolution> errorHandler = new CollectedErrorExcuse<Resolution>(this, promises);
			if (errorHandler.isRejected()) return;

			final CollectedResultsResolver<Resolution> resolver = new CollectedResultsResolver<Resolution>(this, promises);
			cancellationRequested(new AggregateCancellation<Resolution>(this, promises, resolver));
		}
	}

	static final class FirstPromiseResolver<Resolution> extends SingleMessageBroadcaster<Resolution> implements
		Runnable,
		ImmediateResponse<Throwable, Void> {

		private final Collection<Promise<Resolution>> promises;
		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

		private boolean isCancelled;

		FirstPromiseResolver(Collection<Promise<Resolution>> promises) {
			this.promises = promises;
			for (Promise<Resolution> promise : promises) {
				promise.then(new ResolutionProxy<Resolution>(this));
				promise.excuse(this);
			}
			cancellationRequested(this);
		}

		@Override
		public void run() {
			final Lock writeLock = readWriteLock.writeLock();
			writeLock.lock();
			try {
				isCancelled = true;
			} finally {
				writeLock.unlock();
			}

			for (Promise<Resolution> promise : promises) promise.cancel();
			sendRejection(new CancellationException());
		}

		@Override
		public Void respond(Throwable throwable) throws Throwable {
			final Lock readLock = readWriteLock.readLock();
			readLock.lock();
			try {
				if (isCancelled) return null;
			} finally {
				readLock.unlock();
			}

			sendRejection(throwable);

			return null;
		}
	}
}
