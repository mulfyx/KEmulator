package javax.microedition.lcdui.game;

import emulator.Emulator;
import emulator.AppSettings;
import emulator.automation.worker.WorkerFrameCapture;
import emulator.graphics2D.IImage;
import emulator.ui.IScreen;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Graphics;

public abstract class GameCanvas extends Canvas {
	public static final int UP_PRESSED = 2;
	public static final int DOWN_PRESSED = 64;
	public static final int LEFT_PRESSED = 4;
	public static final int RIGHT_PRESSED = 32;
	public static final int FIRE_PRESSED = 256;
	public static final int GAME_A_PRESSED = 512;
	public static final int GAME_B_PRESSED = 1024;
	public static final int GAME_C_PRESSED = 2048;
	public static final int GAME_D_PRESSED = 4096;
	private Graphics graphics;
	private IImage drawingBuffer;
	private IImage drawingXray;

	private static final class CompletedGraphics {
		final int width, height;
		final int[] pixels;

		CompletedGraphics(IImage image) {
			width = image.getWidth();
			height = image.getHeight();
			pixels = image.getData().clone();
		}
	}

	protected GameCanvas(final boolean b) {
		super();
	}

	protected Graphics getGraphics() {
		ensureDrawingBuffer();
		return graphics = new Graphics(drawingBuffer, drawingXray);
	}

	private synchronized void ensureDrawingBuffer() {
		IScreen screen = Emulator.getEmulator().getScreen();
		int width = screen.getWidth(), height = screen.getHeight();
		if (drawingBuffer != null && drawingBuffer.getWidth() == width
				&& drawingBuffer.getHeight() == height) return;
		drawingBuffer = Emulator.getEmulator().newImage(width, height, false, 0xffffff);
		drawingXray = Emulator.getEmulator().newImage(width, height, true, 0);
		if (graphics != null) graphics._reset(drawingBuffer, drawingXray);
	}

	public int getKeyStates() {
		return super.m_keyStates;
	}

	public void paint(final Graphics graphics) {
		ensureDrawingBuffer();
		CompletedGraphics frame = new CompletedGraphics(drawingBuffer);
		graphics.drawRGB(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height, false);
	}

	public void flushGraphics(final int x, final int y, final int w, final int h) {
		if (this != Emulator.getCurrentDisplay().getCurrent()) return;
		long generation = WorkerFrameCapture.paintStarted(this);
		Displayable._checkForSteps(null);
		Displayable._fpsLimiter();
		ensureDrawingBuffer();
		_paintOverlay(graphics == null ? getGraphics() : graphics);
		CompletedGraphics frame = new CompletedGraphics(AppSettings.xrayView ? drawingXray : drawingBuffer);
		Emulator.getEventQueue().gameGraphicsFlush(this, generation,
			frame.pixels, frame.width, frame.height, x, y, w, h);
		Displayable._resetXRayGraphics();
	}

	public void flushGraphics() {
		if (this != Emulator.getCurrentDisplay().getCurrent()) return;
		long generation = WorkerFrameCapture.paintStarted(this);
		Displayable._checkForSteps(null);
		Displayable._fpsLimiter();
		ensureDrawingBuffer();
		_paintOverlay(graphics == null ? getGraphics() : graphics);
		CompletedGraphics frame = new CompletedGraphics(AppSettings.xrayView ? drawingXray : drawingBuffer);
		Emulator.getEventQueue().gameGraphicsFlush(this, generation,
			frame.pixels, frame.width, frame.height);
		Displayable._resetXRayGraphics();
	}

	public void _invokeSizeChanged(int w, int h) {
		ensureDrawingBuffer();
		super._invokeSizeChanged(w, h);
	}
}
