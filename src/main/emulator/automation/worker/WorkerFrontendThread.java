package emulator.automation.worker;

import emulator.Emulator;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.ui.swt.SWTFrontend;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import mjson.Json;

final class WorkerFrontendThread {
	private WorkerFrontendThread() {
	}

	private static RuntimeException propagate(Throwable error) {
		if (error instanceof Error) throw (Error) error;
		if (error instanceof RuntimeException) {
			return (RuntimeException) error;
		}

		return new RuntimeException(error);
	}

	static <T> T call(final Callable<T> callable) {
		return call(callable, 5000L);
	}

	static <T> T call(final Callable<T> callable, long timeoutMs) {
		if (Emulator.getEmulator() instanceof SWTFrontend
				&& org.eclipse.swt.widgets.Display.getCurrent() != SWTFrontend.getDisplay()) {
			final Object startLock = new Object();
			final AtomicBoolean started = new AtomicBoolean();
			final AtomicBoolean abandoned = new AtomicBoolean();
			FutureTask<T> task = new FutureTask<T>(new Callable<T>() {
				public T call() throws Exception {
					synchronized (startLock) {
						if (abandoned.get()) return null;
						started.set(true);
					}
					return callable.call();
				}
			});
			if (timeoutMs <= 0) throw timeout(false, null);
			SWTFrontend.getDisplay().asyncExec(task);
			try {
				return task.get(timeoutMs, TimeUnit.MILLISECONDS);
			} catch (TimeoutException failure) {
				synchronized (startLock) {
					abandoned.set(true);
					task.cancel(false);
				}
				throw timeout(started.get(), failure);
			} catch (InterruptedException failure) {
				synchronized (startLock) {
					abandoned.set(true);
					task.cancel(false);
				}
				Thread.currentThread().interrupt();
				throw timeout(started.get(), failure);
			} catch (ExecutionException failure) {
				throw propagate(failure.getCause());
			}
		}
		try {
			return callable.call();
		} catch (Exception e) {
			throw propagate(e);
		}
	}

	private static AutomationException timeout(boolean started, Throwable cause) {
		return new AutomationException(AutomationErrorCodes.TIMEOUT,
			started ? "The frontend callback did not finish within the operation budget."
				: "The frontend queue did not start the request within the operation budget.",
			Json.object().set("phase", "frontend-queue").set("effect", started ? "unknown" : "none"), cause);
	}
}
