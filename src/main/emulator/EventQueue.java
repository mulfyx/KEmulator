package emulator;

import com.vodafone.v10.graphics.sprite.SpriteCanvas;
import emulator.automation.worker.WorkerFrameCapture;
import emulator.graphics2D.IImage;
import emulator.ui.IScreen;
import net.rim.device.api.system.Application;

import javax.microedition.lcdui.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.HashSet;
import java.util.Set;

public final class EventQueue implements Runnable {
	public static final int EVENT_PAINT = 1;
	public static final int EVENT_CALL = 2;
	public static final int EVENT_SCREEN = 4;
	public static final int EVENT_START = 10;
	public static final int EVENT_EXIT = 11;
	public static final int EVENT_SHOW = 15;
	public static final int EVENT_PAUSE = 16;
	public static final int EVENT_RESUME = 17;
	public static final int EVENT_INPUT = 18;
	public static final int EVENT_COMMAND = 19;
	public static final int EVENT_ITEM_STATE = 20;
	public static final int EVENT_HIDE = 21;
	public static final int EVENT_TRACKED_COMMAND = 22;
	private static final int EVENT_TRACKED_LIFECYCLE = 23;

	public interface CommandDispatchListener {
		void commandFinished(Throwable failure);
	}

	private static final class TrackedCommandAction {
		private final Command command;
		private final Object target;
		private final CommandDispatchListener listener;

		private TrackedCommandAction(
			Command command, Object target, CommandDispatchListener listener) {
			this.command = command;
			this.target = target;
			this.listener = listener;
		}
	}

	public static final class LifecycleAction {
		private final boolean pause;
		private final CountDownLatch done = new CountDownLatch(1);
		private int pendingCallbacks = 1;
		private Throwable failure;

		private LifecycleAction(boolean pause) {
			this.pause = pause;
		}

		public boolean await(long timeoutMs) throws InterruptedException {
			return done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
		}

		public synchronized Throwable failure() {
			return failure;
		}

		private synchronized void callbackStarted() {
			pendingCallbacks++;
		}

		private synchronized void callbackFinished(Throwable failure) {
			if (this.failure == null) {
				this.failure = failure;
			}
			if (--pendingCallbacks == 0) {
				done.countDown();
			}
		}
	}

	boolean running;
	private int[] events;
	private int count;
	private final Vector eventArguments = new Vector();
	private final Thread eventThread;
	private volatile boolean paused;
	private final ReentrantLock lifecycleLock = new ReentrantLock();
	private LifecycleAction pendingLifecycle;
	private final ThreadLocal<LifecycleAction> currentLifecycleDispatch = new ThreadLocal<LifecycleAction>();
	private volatile LifecycleAction queuedLifecycle;
	private final Object callbackLock = new Object();
	private boolean alive;
	private final Object eventLock = new Object();

	private final Object repaintLock = new Object();
	private Displayable renderedOwner;
	private long renderedGeneration = -1L;
	private boolean repaintPending;
	private int repaintX, repaintY, repaintW, repaintH;

	private int[][] inputs;
	private int inputsCount;
	private int nextInputSequence = 1;
	private final Set<Integer> pendingInputDispatch = new HashSet<Integer>();
	private final ThreadLocal<Integer> currentInputDispatch = new ThreadLocal<Integer>();
	private final Object inputDispatchLock = new Object();
	private final InputThread input = new InputThread();
	private final Thread inputThread;
	private String pointerNumber;

	private Timer screenTimer;
	private TimerTask screenTimerTask;

	public EventQueue() {
		events = new int[128];
		inputs = new int[128][];
		paused = false;
		running = true;

		eventThread = new Thread(this, "KEmulator-EventQueue");
		eventThread.setPriority(3);
		eventThread.start();

		inputThread = new Thread(input, "KEmulator-InputQueue");
		inputThread.setPriority(3);
		inputThread.start();

		Thread daemonThread = new Thread(() -> {
			try {
				Thread.sleep(Integer.MAX_VALUE);
			} catch (Exception ignored) {}
		});
		daemonThread.setDaemon(true);
		daemonThread.start();

		Thread crashThread = new Thread() {
			public void run() {
				long time = System.currentTimeMillis();
				try {
					while (running) {
						if (alive || paused || AppSettings.steps >= 0) {
							time = System.currentTimeMillis();
							alive = false;
						} else if ((System.currentTimeMillis() - time) > 5000) {
							time = System.currentTimeMillis();
							if (!AppSettings.uei) {
								Emulator.getEmulator().getLogStream().println("Event thread is not responding! Is it dead locked?");
							}
						}
						Displayable._updateFpsCounter();
						sleep(1000);
					}
				} catch (InterruptedException ignored) {}
			}
		};
		crashThread.start();
	}

	public void stop() {
		running = false;
		synchronized (input.readLock) {
			input.readLock.notifyAll();
		}
	}

	public boolean isEventThread() {
		return Thread.currentThread() == eventThread;
	}

	/** Identifies a permission raised by the input callback on this thread. */
	public int currentInputSequence() {
		Integer sequence = currentInputDispatch.get();
		return sequence == null ? 0 : sequence.intValue();
	}

	public Object currentLifecycleOperation() {
		return currentLifecycleDispatch.get();
	}

	public void keyPress(int n) {
		keyPressTracked(n);
	}

	public int keyPressTracked(int n) {
		if (n == 10000) return 0;
		int sequence = nextInputSequence();
		if (AppSettings.synchronizeKeyEvents) {
			queueInput(new int[] {0, n, 0, -1, sequence});
		} else input.queue(0, n, 0, -1, sequence);
		return sequence;
	}

	public void keyRelease(int n) {
		keyReleaseTracked(n);
	}

	public int keyReleaseTracked(int n) {
		if (n == 10000) return 0;
		int sequence = nextInputSequence();
		if (AppSettings.synchronizeKeyEvents) {
			queueInput(new int[] {1, n, 0, -1, sequence});
		} else input.queue(1, n, 0, -1, sequence);
		return sequence;
	}

	public void keyRepeat(int n) {
		if (n == 10000) return;
		int sequence = nextInputSequence();
		if (AppSettings.synchronizeKeyEvents) {
			queueInput(new int[] {2, n, 0, -1, sequence});
		} else input.queue(2, n, 0, -1, sequence);
	}

	public void mouseDown(int x, int y, int pointer) {
		mouseDownTracked(x, y, pointer);
	}

	public int mouseDownTracked(int x, int y, int pointer) {
		int sequence = nextInputSequence();
		if (AppSettings.synchronizeKeyEvents) {
			queueInput(new int[] {0, x, y, pointer, sequence});
		} else input.queue(0, x, y, pointer, sequence);
		return sequence;
	}

	public void mouseUp(int x, int y, int pointer) {
		mouseUpTracked(x, y, pointer);
	}

	public int mouseUpTracked(int x, int y, int pointer) {
		int sequence = nextInputSequence();
		if (AppSettings.synchronizeKeyEvents) {
			queueInput(new int[] {1, x, y, pointer, sequence});
		} else input.queue(1, x, y, pointer, sequence);
		return sequence;
	}

	public void mouseDrag(int x, int y, int pointer) {
		mouseDragTracked(x, y, pointer);
	}

	public int mouseDragTracked(int x, int y, int pointer) {
		int sequence = nextInputSequence();
		if (AppSettings.synchronizeKeyEvents) {
			queueInput(new int[] {2, x, y, pointer, sequence});
		} else input.queue(2, x, y, pointer, sequence);
		return sequence;
	}

	private int nextInputSequence() {
		synchronized (inputDispatchLock) {
			if (nextInputSequence == Integer.MAX_VALUE) {
				nextInputSequence = 1;
			}
			int sequence = nextInputSequence++;
			pendingInputDispatch.add(Integer.valueOf(sequence));
			return sequence;
		}
	}

	private void markInputDelivered(int[] inputEvent) {
		if (inputEvent == null || inputEvent.length < 5) {
			return;
		}
		synchronized (inputDispatchLock) {
			pendingInputDispatch.remove(Integer.valueOf(inputEvent[4]));
			inputDispatchLock.notifyAll();
		}
	}

	public boolean isInputDispatched(int sequence) {
		synchronized (inputDispatchLock) {
			return sequence <= 0 || !pendingInputDispatch.contains(Integer.valueOf(sequence));
		}
	}

	public boolean waitForInputDispatch(int sequence, long timeoutMs) throws InterruptedException {
		if (sequence <= 0) {
			return true;
		}
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
		synchronized (inputDispatchLock) {
			while (pendingInputDispatch.contains(Integer.valueOf(sequence))) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0L) {
					return false;
				}
				TimeUnit.NANOSECONDS.timedWait(inputDispatchLock, remaining);
			}
			return true;
		}
	}

	public boolean isPaused() {
		return paused;
	}

	/** Returns whether the state changed, after all lifecycle callbacks finish. */
	public boolean setPausedAndWait(boolean pause, long timeoutMs)
		throws InterruptedException, TimeoutException {
		long deadline = System.nanoTime()
			+ TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
		LifecycleAction action = requestPaused(pause, timeoutMs);
		if (action == null) return false;
		awaitLifecycle(action, deadline);
		if (action.failure() != null) {
			throw new RuntimeException("MIDlet lifecycle callback failed", action.failure());
		}
		return true;
	}

	/** Admission is bounded separately from callback completion. */
	public LifecycleAction requestPaused(boolean pause, long timeoutMs)
		throws InterruptedException, TimeoutException {
		long deadline = System.nanoTime()
			+ TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
		if (!lifecycleLock.tryLock(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS)) {
			throw new TimeoutException("MIDlet lifecycle operation was not admitted");
		}
		try {
			// A timed-out request can still be in flight. Wait for it before
			// deciding whether a retry needs another lifecycle event.
			if (pendingLifecycle != null) {
				if (pendingLifecycle.pause == pause && pendingLifecycle.done.getCount() != 0L) {
					return pendingLifecycle;
				}
				awaitLifecycle(pendingLifecycle, deadline);
				pendingLifecycle = null;
			}
			if (paused == pause) {
				return null;
			}
			if (System.nanoTime() >= deadline) throw new TimeoutException("MIDlet lifecycle operation was not admitted");

			LifecycleAction action = new LifecycleAction(pause);
			pendingLifecycle = action;
			// Only one tracked lifecycle action can be in flight. Keep its
			// payload separate from the arguments of normal LCDUI events.
			queuedLifecycle = action;
			// Resume must wake the paused queue, but its caller still waits
			// for startApp and showNotify rather than this admission flag.
			if (!pause) {
				paused = false;
			}
			queue(EVENT_TRACKED_LIFECYCLE);
			return action;
		} finally {
			lifecycleLock.unlock();
		}
	}

	private void awaitLifecycle(LifecycleAction action, long deadline)
		throws InterruptedException, TimeoutException {
		if (!action.done.await(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
			throw new TimeoutException("MIDlet lifecycle callback did not finish");
		}
	}

	public void sizeChanged(int x, int y) {
		queue(Integer.MIN_VALUE | (x & 0xFFF) | (y & 0xFFF) << 12);
	}

	private void queueInput(int[] o) {
		synchronized (this) {
			inputs[inputsCount++] = o;
			if (inputsCount >= inputs.length) {
				System.arraycopy(inputs, 0, inputs = new int[inputs.length * 2][], 0, inputsCount);
			}
		}
		queue(EVENT_INPUT);
	}

	public synchronized void queue(int n) {
		if (n == EVENT_PAINT || n == EVENT_SCREEN) {
			if (repaintPending)
				return;
			repaintPending = true;
		}
		if (n == EVENT_SHOW || n == EVENT_RESUME) {
			paused = false;
		}
		events[count++] = n;
		if (count >= events.length) {
			System.arraycopy(events, 0, events = new int[events.length * 2], 0, count);
		}
		synchronized (eventLock) {
			eventLock.notify();
		}
	}

	private synchronized int nextEvent() {
		if (count == 0) return 0;
		int n = events[0];
		System.arraycopy(events, 1, events, 0, events.length - 1);
		count--;
		return n;
	}

	private Object nextArgument() {
		Object a = null;
		synchronized (eventArguments) {
			if (!eventArguments.isEmpty()) {
				a = eventArguments.remove(0);
			}
		}
		return a;
	}

	public void queueRepaint() {
		synchronized (repaintLock) {
			repaintX = repaintY = repaintW = repaintH = -1;
			queue(EVENT_PAINT);
		}
	}

	public void queueRepaint(int x, int y, int w, int h) {
		if (AppSettings.j2lStyleFpsLimit)
			Displayable._fpsLimiter();

		synchronized (repaintLock) {
			int x1 = x,
					y1 = y,
					x2 = x + w,
					y2 = y + h;
			Displayable d = getCurrent();
			int sw = d.getWidth(), sh = d.getHeight();
			if (repaintX != -1) {
				x1 = Math.min(repaintX, x1);
				y1 = Math.min(repaintY, y1);
				x2 = Math.max(repaintX + repaintW, x2);
				y2 = Math.max(repaintY + repaintH, y2);
			}
			if (x1 < 0) x1 = 0;
			if (y1 < 0) y1 = 0;
			if (x2 > sw) x2 = sw;
			if (y2 > sh) y2 = sh;
			repaintX = x1;
			repaintY = y1;
			repaintW = x2 - x1;
			repaintH = y2 - y1;
			queue(EVENT_PAINT);
		}
	}

	public void itemStateChanged(Item item) {
		eventArguments.add(item);
		queue(EVENT_ITEM_STATE);
	}

	public void commandAction(Command command, Item item) {
		eventArguments.add(command);
		eventArguments.add(item);
		queue(EVENT_COMMAND);
	}

	public void commandAction(Command command, Displayable d) {
		eventArguments.add(command);
		eventArguments.add(d);
		queue(EVENT_COMMAND);
	}

	public void commandActionTracked(
		Command command, Object target, CommandDispatchListener listener) {
		synchronized (eventArguments) {
			eventArguments.add(new TrackedCommandAction(command, target, listener));
		}
		queue(EVENT_TRACKED_COMMAND);
	}

	public void gameGraphicsFlush() {
		Displayable owner = getCurrent();
		long generation = WorkerFrameCapture.paintStarted(owner);
		synchronized (repaintLock) {
			if (owner != getCurrent()) return;
			IScreen scr = Emulator.getEmulator().getScreen();
			if (AppSettings.asyncFlush) {
				final IImage screenImage = scr.getScreenImg();
				final IImage backBufferImage2 = scr.getBackBufferImage();
				final IImage xRayScreenImage2 = scr.getXRayScreenImage();
				(AppSettings.xrayView ? xRayScreenImage2 : backBufferImage2).cloneImage(screenImage);
			}
			WorkerFrameCapture.publish(owner, scr.getScreenImg(), generation);
			renderedOwner = owner;
			renderedGeneration = generation;
			scr.repaint();
		}
		emulator.automation.worker.AutomationWorkerRuntime.onFrameRendered();
	}

	public void gameGraphicsFlush(int x, int y, int w, int h) {
		Displayable owner = getCurrent();
		long generation = WorkerFrameCapture.paintStarted(owner);
		synchronized (repaintLock) {
			if (owner != getCurrent()) return;
			IScreen scr = Emulator.getEmulator().getScreen();
			if (AppSettings.asyncFlush) {
				final IImage screenImage = scr.getScreenImg();
				final IImage backBufferImage2 = scr.getBackBufferImage();
				final IImage xRayScreenImage2 = scr.getXRayScreenImage();
				(AppSettings.xrayView ? xRayScreenImage2 : backBufferImage2).cloneImage(screenImage, x, y, w, h);
			}
			WorkerFrameCapture.publish(owner, scr.getScreenImg(), generation);
			renderedOwner = owner;
			renderedGeneration = generation;
			scr.repaint();
		}
		emulator.automation.worker.AutomationWorkerRuntime.onFrameRendered();
	}

	public void gameGraphicsFlush(Displayable owner, long generation, int[] pixels,
			int width, int height) {
		gameGraphicsFlush(owner, generation, pixels, width, height, 0, 0, width, height);
	}

	public void gameGraphicsFlush(Displayable owner, long generation, int[] pixels,
			int width, int height, int x, int y, int w, int h) {
		synchronized (repaintLock) {
			if (owner != getCurrent() || generation != WorkerFrameCapture.paintStarted(owner)) return;
			IScreen screen = Emulator.getEmulator().getScreen();
			IImage output = screen.getScreenImg();
			if (width != output.getWidth() || height != output.getHeight()) return;
			int x1 = Math.max(0, x), y1 = Math.max(0, y);
			int x2 = (int) Math.min(width, (long) x + w);
			int y2 = (int) Math.min(height, (long) y + h);
			if (x2 <= x1 || y2 <= y1) return;
			if (x1 == 0 && y1 == 0 && x2 == width && y2 == height) {
				output.setData(pixels);
			} else {
				int[] outputPixels;
				if (renderedOwner == owner && renderedGeneration == generation) {
					outputPixels = output.getData().clone();
				} else {
					outputPixels = new int[width * height];
					java.util.Arrays.fill(outputPixels, 0xffffffff);
				}
				for (int row = y1; row < y2; row++) {
					System.arraycopy(pixels, row * width + x1, outputPixels,
						row * width + x1, x2 - x1);
				}
				output.setData(outputPixels);
			}
			WorkerFrameCapture.publish(owner, output, generation);
			renderedOwner = owner;
			renderedGeneration = generation;
			screen.repaint();
		}
		emulator.automation.worker.AutomationWorkerRuntime.onFrameRendered();
	}

	private void initializeFrame(Displayable owner, long generation, IImage buffer, IImage xray) {
		if (renderedOwner == owner && renderedGeneration == generation) return;
		int[] pixels = new int[buffer.getWidth() * buffer.getHeight()];
		java.util.Arrays.fill(pixels, 0xffffffff);
		buffer.setData(pixels);
		if (xray != null) xray.setData(new int[xray.getWidth() * xray.getHeight()]);
	}

	public void gameGraphicsBlit(Displayable owner, Image image, int x, int y) {
		synchronized (repaintLock) {
			if (owner != getCurrent()) return;
			long generation = WorkerFrameCapture.paintStarted(owner);
			IScreen screen = Emulator.getEmulator().getScreen();
			IImage output = screen.getScreenImg();
			initializeFrame(owner, generation, output, null);
			Graphics graphics = new Graphics(output, screen.getXRayScreenImage());
			graphics.drawImage(image, x, y, 0);
			WorkerFrameCapture.publish(owner, output, generation);
			renderedOwner = owner;
			renderedGeneration = generation;
			screen.repaint();
		}
		emulator.automation.worker.AutomationWorkerRuntime.onFrameRendered();
	}

	public void serviceRepaints() {
		if (Settings.ignoreServiceRepaints) return;
		synchronized (callbackLock) {
			if (!repaintPending) return;
			repaintPending = false;
			int x = repaintX, y = repaintY, w = repaintW, h = repaintH;
			repaintX = repaintY = repaintW = repaintH = -1;
			internalRepaint(x, y, w, h);
		}
		if (!AppSettings.j2lStyleFpsLimit)
			Displayable._fpsLimiter();
		emulator.automation.worker.AutomationWorkerRuntime.onFrameRendered();
	}

	public void notifyHidden(Displayable d) {
		eventArguments.add(d);
		queue(EVENT_HIDE);
	}

	public void run() {
		int event = 0;
		try {
			while (running) {
				alive = true;
				if (Emulator.getMIDlet() == null || paused) {
					Thread.sleep(5);
					continue;
				}
				try {
					switch (event = nextEvent()) {
						case EVENT_PAINT: {
							synchronized (callbackLock) {
								if (!repaintPending) break;
								repaintPending = false;
								int x = repaintX, y = repaintY, w = repaintW, h = repaintH;
								repaintX = repaintY = repaintW = repaintH = -1;
								internalRepaint(x, y, w, h);
							}
							if (!AppSettings.j2lStyleFpsLimit)
								Displayable._fpsLimiter();
							emulator.automation.worker.AutomationWorkerRuntime.onFrameRendered();
							break;
						}
						case EVENT_CALL: {
							processSerialEvent();
							break;
						}
						case EVENT_SCREEN: {
							Displayable d = getCurrent();
							repaintPending = false;
							if (!(d instanceof Screen)) break;
							synchronized (repaintLock) {
								IScreen scr = Emulator.getEmulator().getScreen();
								long generation = WorkerFrameCapture.paintStarted(d);
								if (!((Screen) d)._isSWT()) {
									final IImage backBufferImage3 = scr.getBackBufferImage();
									final IImage xRayScreenImage3 = scr.getXRayScreenImage();
									initializeFrame(d, generation, backBufferImage3, xRayScreenImage3);
									((Screen) d)._invokePaint(new Graphics(backBufferImage3, xRayScreenImage3));
									if (AppSettings.asyncFlush) {
										(AppSettings.xrayView ? xRayScreenImage3 : backBufferImage3)
												.cloneImage(scr.getScreenImg());
									}
									WorkerFrameCapture.publish(d, scr.getScreenImg(), generation);
									renderedOwner = d;
									renderedGeneration = generation;
								}
								scr.repaint();
							}
							emulator.automation.worker.AutomationWorkerRuntime.onFrameRendered();
							int interval = ((Screen) d)._repaintInterval();
							if (interval > 0) {
								synchronized (callbackLock) {
									if (screenTimer == null) {
										screenTimer = new Timer();
									}
									if (screenTimerTask != null) {
										try {
											screenTimerTask.cancel();
										} catch (Exception ignored) {
										}
										screenTimerTask = null;
									}
									screenTimerTask = new ScreenTimerTask();
									screenTimer.schedule(screenTimerTask, interval);
								}
							}
							break;
						}
						case EVENT_START: {
							if (Emulator.getMIDlet() == null) break;
							Thread t = new Thread(new InvokeStartAppRunnable(true), "KEmulator-StartApp");
							t.setPriority(5);
							t.start();
							break;
						}
						case EVENT_EXIT: {
							this.stop();
							if (Emulator.getMIDlet() == null) break;
							Thread t = new Thread(new InvokeDestroyAppRunnable(this), "KEmulator-DestroyApp");
							t.setPriority(5);
							t.start();
							break;
						}
						case EVENT_SHOW: {
							Displayable d = getCurrent();
							if (!(getCurrent() instanceof Canvas)) break;
							((Canvas) d)._invokeShowNotify();
							break;
						}
						case EVENT_PAUSE: {
							Displayable d = getCurrent();
							// The MIDlet lifecycle must pause regardless of the current
							// displayable kind; hideNotify is Canvas-only.
							if (d instanceof Canvas) ((Canvas) d)._invokeHideNotify();
							this.paused = true;
							if (AppSettings.startAppOnResume) {
								try {
									Emulator.getMIDlet().invokePauseApp();
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
							break;
						}
						case EVENT_RESUME: {
							Displayable d = getCurrent();
							if (AppSettings.startAppOnResume) {
								try {
									Thread t = new Thread(new InvokeStartAppRunnable(false));
									t.setPriority(5);
									t.start();
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
							if (!(d instanceof Canvas)) break;
							((Canvas) d)._invokeShowNotify();
							break;
						}
						case EVENT_TRACKED_LIFECYCLE: {
							LifecycleAction action = queuedLifecycle;
							queuedLifecycle = null;
							dispatchLifecycle(action);
							break;
						}
						case EVENT_INPUT: {
							if (inputsCount <= 0) break;
							int[] e;
							synchronized (this) {
								e = inputs[0];
								System.arraycopy(inputs, 1, inputs, 0, inputs.length - 1);
								inputsCount--;
							}
							if (e == null) break;
							synchronized (callbackLock) {
								dispatchInputEvent(e);
							}
							// skip 1ms delay
							continue;
						}
						case EVENT_ITEM_STATE: {
							Item item = (Item) nextArgument();
							Screen screen = item._getParent();
							if (!(screen instanceof Form) || screen != getCurrent()) break;
							((Form) screen)._itemStateChanged(item);
							break;
						}
						case EVENT_COMMAND: {
							Command cmd = (Command) nextArgument();
							Object target = nextArgument();
							if (target instanceof Item) {
								((Item) target)._callCommandAction(cmd);
							} else {
								((Displayable) target)._callCommandAction(cmd);
							}
							break;
						}
						case EVENT_TRACKED_COMMAND: {
							TrackedCommandAction action = (TrackedCommandAction) nextArgument();
							Throwable failure = null;
							try {
								if (action.target instanceof Item) {
									((Item) action.target)._callCommandAction(action.command);
								} else {
									((Displayable) action.target)._callCommandAction(action.command);
								}
							} catch (Throwable throwable) {
								failure = throwable;
							} finally {
								action.listener.commandFinished(failure);
							}
							if (failure instanceof RuntimeException) {
								throw (RuntimeException) failure;
							}
							if (failure instanceof Error) {
								throw (Error) failure;
							}
							if (failure != null) {
								throw new RuntimeException(failure);
							}
							break;
						}
						case EVENT_HIDE: {
							Displayable d = (Displayable) nextArgument();
							if (d instanceof Canvas) {
								((Canvas) d)._invokeHideNotify();
							}
							break;
						}
						case 0: {
							synchronized (eventLock) {
								eventLock.wait(1000);
							}
							break;
						}
						default: {
							if ((event & Integer.MIN_VALUE) == 0) break;
							Displayable d = getCurrent();
							if (d == null) break;
							d._invokeSizeChanged(
									event & 0xFFF,
									(event >> 12) & 0xFFF);
							break;
						}
					}
					Thread.yield();
				} catch (Throwable e) {
					System.err.println("Exception in Event Thread!");
					System.err.println("Event: " + event);
					e.printStackTrace();
				}
			}
		} catch (InterruptedException ignored) {}
	}

	private void dispatchLifecycle(final LifecycleAction action) {
		if (action == null) return;
		currentLifecycleDispatch.set(action);
		Throwable failure = null;
		try {
			Displayable current = getCurrent();
			if (action.pause) {
				if (current instanceof Canvas) ((Canvas) current)._invokeHideNotify();
				paused = true;
				if (AppSettings.startAppOnResume) {
					Emulator.getMIDlet().invokePauseApp();
				}
			} else {
				if (AppSettings.startAppOnResume) {
					Thread startThread = new Thread(new Runnable() {
						public void run() {
							currentLifecycleDispatch.set(action);
							Throwable failure = null;
							try {
								new InvokeStartAppRunnable(false).run();
							} catch (Throwable throwable) {
								failure = throwable;
							} finally {
								currentLifecycleDispatch.remove();
								action.callbackFinished(failure);
							}
						}
					}, "KEmulator-ResumeApp");
					startThread.setPriority(5);
					action.callbackStarted();
					try {
						startThread.start();
					} catch (Throwable throwable) {
						action.callbackFinished(throwable);
						throw throwable;
					}
				}
				if (current instanceof Canvas) ((Canvas) current)._invokeShowNotify();
			}
		} catch (Throwable throwable) {
			failure = throwable;
		} finally {
			currentLifecycleDispatch.remove();
			action.callbackFinished(failure);
		}
	}

	private void processSerialEvent() {
		Runnable r = null;
		synchronized (eventArguments) {
			if (!eventArguments.isEmpty()) {
				r = ((Runnable) eventArguments.remove(0));
			}
		}
		if (r == null) return;
		synchronized (callbackLock) {
			r.run();
		}
	}

	public void callSerially(Runnable run) {
		synchronized (eventArguments) {
			eventArguments.add(run);
		}
		queue(EVENT_CALL);
	}

	public boolean callAndWait(final Runnable run, long timeoutMs) throws InterruptedException {
		if (Thread.currentThread() == eventThread) {
			run.run();
			return true;
		}
		final CountDownLatch done = new CountDownLatch(1);
		final Throwable[] failure = new Throwable[1];
		callSerially(new Runnable() {
			public void run() {
				try {
					run.run();
				} catch (Throwable t) {
					failure[0] = t;
				} finally {
					done.countDown();
				}
			}
		});
		boolean completed = done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
		if (completed && failure[0] != null) {
			if (failure[0] instanceof RuntimeException) {
				throw (RuntimeException) failure[0];
			}
			if (failure[0] instanceof Error) {
				throw (Error) failure[0];
			}
			throw new RuntimeException(failure[0]);
		}
		return completed;
	}

	public boolean waitUntilIdle(long timeoutMs) throws InterruptedException {
		return callAndWait(new Runnable() {
			public void run() {
			}
		}, timeoutMs);
	}

	public void waitRepaint() throws InterruptedException {
		if (Thread.currentThread() == eventThread) return;
		while (repaintPending) {
			Thread.sleep(1);
		}
	}

	private void internalRepaint(int x, int y, int w, int h) {
		synchronized (repaintLock) {
			repaintCanvas(x, y, w, h);
		}
	}

	private void repaintCanvas(int x, int y, int w, int h) {
		repaintPending = false;
		try {
			Canvas canvas = Emulator.getCanvas();
			if (canvas == null
					|| Emulator.getCurrentDisplay().getCurrent() != Emulator.getCanvas()) {
				return;
			}
			long generation = WorkerFrameCapture.paintStarted(canvas);
			if (AppSettings.xrayView) Displayable._resetXRayGraphics();
			IScreen scr = Emulator.getEmulator().getScreen();
			IImage backBufferImage, xRayScreenImage;
			if (canvas instanceof SpriteCanvas) {
				backBufferImage = SpriteCanvas._virtualImage._getImpl();
				xRayScreenImage = null;
			} else {
				backBufferImage = scr.getBackBufferImage();
				xRayScreenImage = scr.getXRayScreenImage();
			}
			Displayable._checkForSteps(callbackLock);
			initializeFrame(canvas, generation, backBufferImage, xRayScreenImage);
			try {
				if (x == -1) { // full repaint
					canvas._invokePaint(backBufferImage, xRayScreenImage);
				} else {
					canvas._invokePaint(backBufferImage, xRayScreenImage, x, y, w, h);
				}
			} catch (Exception ex) {
				ex.printStackTrace();
				return;
			}
			if (canvas instanceof SpriteCanvas) {
				if (!((SpriteCanvas) canvas)._skipCopy) {
					backBufferImage.cloneImage(scr.getScreenImg());
					WorkerFrameCapture.publish(canvas, scr.getScreenImg(), generation);
					renderedOwner = canvas;
					renderedGeneration = generation;
					scr.repaint();
				}
				return;
			}
			if (AppSettings.asyncFlush) {
				(AppSettings.xrayView ? xRayScreenImage : backBufferImage)
						.cloneImage(scr.getScreenImg());
			}
			WorkerFrameCapture.publish(canvas, scr.getScreenImg(), generation);
			renderedOwner = canvas;
			renderedGeneration = generation;
			scr.repaint();
		} catch (Exception e) {
			System.err.println("Exception in repaint!");
			e.printStackTrace();
		}
	}

	private void dispatchInputEvent(int[] event) {
		currentInputDispatch.set(Integer.valueOf(event[4]));
		try {
			processInputEvent(event);
		} finally {
			currentInputDispatch.remove();
			markInputDelivered(event);
			emulator.automation.worker.AutomationWorkerRuntime.onInputDispatched();
		}
	}

	void processInputEvent(int[] o) {
		if (o == null) return;
		Displayable d = getCurrent();
		if (d == null) return;
		boolean canv = d instanceof Canvas;
		int pointer = o[3];
		if (pointer == -1) {
			// keyboard
			int n = o[1];
			switch (o[0]) {
				case 0: {
					Application.internalKeyPress(n);
					if (d.handleSoftKeyAction(n, true)) return;
					if (canv) ((Canvas) d)._invokeKeyPressed(n);
					else ((Screen) d)._invokeKeyPressed(n);
					break;
				}
				case 1: {
					Application.internalKeyRelease(n);
					if (d.handleSoftKeyAction(n, false)) return;
					if (canv) ((Canvas) d)._invokeKeyReleased(n);
					else ((Screen) d)._invokeKeyReleased(n);
					break;
				}
				case 2:
					if (canv) ((Canvas) d)._invokeKeyRepeated(n);
					else ((Screen) d)._invokeKeyRepeated(n);
					break;
			}
		} else {
			pointerNumber = String.valueOf(pointer);
			// mouse
			int x = o[1];
			int y = o[2];
			switch (o[0]) {
				case 0:
					if (canv) ((Canvas) d).invokePointerPressed(x, y);
					else ((Screen) d)._invokePointerPressed(x, y);
					break;
				case 1:
					if (canv) ((Canvas) d).invokePointerReleased(x, y);
					else ((Screen) d)._invokePointerReleased(x, y);
					break;
				case 2:
					if (canv) ((Canvas) d).invokePointerDragged(x, y);
					else ((Screen) d)._invokePointerDragged(x, y);
					break;
			}
			pointerNumber = null;
		}
	}

	private Displayable getCurrent() {
		return Emulator.getCurrentDisplay().getCurrent();
	}

	public String getPointerNumber() {
		return pointerNumber;
	}

	class InputThread implements Runnable {
		private Object[] elements;
		private final Object readLock = new Object();
		private int count;

		private InputThread() {
			elements = new Object[16];
		}

		public void queue(int state, int arg1, int arg2, int pointer, int sequence) {
			append(new int[]{state, arg1, arg2, pointer, sequence});
		}

		private void append(Object o) {
			synchronized (this) {
				if (count + 1 >= elements.length) {
					System.arraycopy(elements, 0, elements = new Object[elements.length * 2], 0, count);
				}
				elements[count++] = o;
			}
			synchronized (readLock) {
				readLock.notifyAll();
			}
		}

		private synchronized boolean hasQueuedInput() {
			return count > 0;
		}

		public void run() {
			while (running) {
				try {
					synchronized (readLock) {
						while (running && !hasQueuedInput()) readLock.wait();
					}
					while (running && (Emulator.getMIDlet() == null || paused)) {
						Thread.sleep(5);
					}
					while (running) {
						int[] o;
						synchronized (this) {
							if (count == 0) break;
							o = (int[]) elements[0];
							System.arraycopy(elements, 1, elements, 0, count - 1);
							elements[--count] = null;
						}
						try {
							if (AppSettings.synchronizeKeyEvents) {
								synchronized (callbackLock) {
									dispatchInputEvent(o);
								}
							} else dispatchInputEvent(o);
						} catch (Throwable e) {
							System.err.println("Exception in Input Thread!");
							e.printStackTrace();
						}
					}
				} catch (Throwable e) {
					System.err.println("Exception in Input Thread!");
					e.printStackTrace();
				}
			}
		}
	}

	private class ScreenTimerTask extends TimerTask {
		public void run() {
			queue(EVENT_SCREEN);
		}
	};
}
