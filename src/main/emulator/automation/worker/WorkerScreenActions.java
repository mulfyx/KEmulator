package emulator.automation.worker;

import emulator.Emulator;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.automation.shared.OperationDeadline;
import emulator.ui.IEmulatorFrontend;
import emulator.ui.IScreen;
import java.util.concurrent.Callable;
import mjson.Json;

final class WorkerScreenActions {
	private static final int MAX_DIMENSION = emulator.automation.shared.AutomationLimits.MAX_DIMENSION;

	private WorkerScreenActions() {
	}

	private static IScreen requireScreen() {
		IEmulatorFrontend frontend = Emulator.getEmulator();
		IScreen screen = frontend == null ? null : frontend.getScreen();
		if (screen == null) {
			throw new AutomationException(
				AutomationErrorCodes.APP_INPUT_UNAVAILABLE,
				"Emulator screen is not available");
		}

		return screen;
	}

	private static void validateDimension(int value, String name) {
		if (value < 1 || value > MAX_DIMENSION) {
			throw new AutomationException(
				AutomationErrorCodes.INVALID_REQUEST,
				name + " must be between 1 and " + MAX_DIMENSION + ": " + value,
				Json.object().set(name, value));
		}
	}

	private static Json applySize(final Json request, final Integer requestedWidth, final Integer requestedHeight) {
		final OperationDeadline deadline = OperationDeadline.fromRequest(request, 5000L);
		final long frameRevisionBefore = WorkerEventModel.frameRevision();
		Json applied = WorkerFrontendThread.call(new Callable<Json>() {
			public Json call() {
				if (deadline.timedOut()) {
					throw new AutomationException(AutomationErrorCodes.TIMEOUT,
						"Screen resize budget expired before applying the size",
						Json.object().set("phase", "frontend-queue").set("effectUnknown", false).set("performed", false));
				}
				IScreen screen = requireScreen();
				RevisionGuard.check(request);
				int oldWidth = screen.getWidth();
				int oldHeight = screen.getHeight();
				int width = requestedWidth == null ? oldHeight : requestedWidth.intValue();
				int height = requestedHeight == null ? oldWidth : requestedHeight.intValue();
				validateDimension(width, "width");
				validateDimension(height, "height");
				long oldRevision = WorkerEventModel.revision();
				screen.setSize(width, height);
				long newRevision = WorkerEventModel.stateChanged(
					"screen-resized",
					Json.object()
						.set("oldWidth", oldWidth)
						.set("oldHeight", oldHeight)
						.set("width", screen.getWidth())
						.set("height", screen.getHeight()));
				screen.repaint();

				return Json.object()
					.set("oldWidth", oldWidth)
					.set("oldHeight", oldHeight)
					.set("width", screen.getWidth())
					.set("height", screen.getHeight())
					.set("oldRevision", oldRevision)
					.set("newRevision", newRevision);
			}
		}, deadline.remainingMillis());

		WorkerCommands.invalidate();
		try {
			if (request.at("waitFrame", false).asBoolean()) {
				if (!WorkerEventModel.awaitFrameAfter(frameRevisionBefore, deadline.remainingMillis())) {
					throw new AutomationException(
						AutomationErrorCodes.TIMEOUT,
						"Timed out waiting for a frame after the resize",
						Json.object().set("phase", "frame-wait"));
				}
			}
			applied.set("frameRevision", WorkerEventModel.frameRevision());
			applied.set("state", WorkerSessionSnapshot.build(false, deadline.remainingMillis()));
			applied.set("elapsedMs", deadline.elapsedMillis());
			return applied;
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw timeoutAfterApply(applied, deadline,
				"Interrupted while waiting for a frame after the resize", Json.object().set("phase", "frame-wait"), failure);
		} catch (AutomationException failure) {
			if (!AutomationErrorCodes.TIMEOUT.equals(failure.code)) throw failure;
			throw timeoutAfterApply(applied, deadline, failure.getMessage(), failure.details, failure);
		}
	}

	private static AutomationException timeoutAfterApply(Json applied, OperationDeadline deadline,
		String message, Json context, Throwable cause) {
		Json details = context != null && context.isObject() ? context.dup() : Json.object();
		details.set("timeoutMs", deadline.timeoutMillis()).set("elapsedMs", deadline.elapsedMillis())
			.set("applied", applied.dup()).set("frameRevision", WorkerEventModel.frameRevision())
			.set("performed", true).set("effectUnknown", false).set("effect", "applied");
		return new AutomationException(AutomationErrorCodes.TIMEOUT, message, details, cause);
	}

	static Json resize(Json request) {
		int width = request.at("width", -1).asInteger();
		int height = request.at("height", -1).asInteger();
		validateDimension(width, "width");
		validateDimension(height, "height");

		return applySize(request, Integer.valueOf(width), Integer.valueOf(height));
	}

	static Json rotate(Json request) {
		// Width and height are swapped atomically on the frontend thread.
		return applySize(request, null, null);
	}
}
