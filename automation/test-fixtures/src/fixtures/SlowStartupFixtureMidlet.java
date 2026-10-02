package fixtures;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.midlet.MIDlet;

/** Startup exceeds a short open budget, then becomes usable in the same worker. */
public final class SlowStartupFixtureMidlet extends MIDlet {
    protected void startApp() {
        System.out.println("AGENT slow startup entered");
        try { Thread.sleep(8000L); } catch (InterruptedException ignored) { }
        Display.getDisplay(this).setCurrent(new Form("Slow startup ready"));
        System.out.println("AGENT slow startup completed");
    }
    protected void pauseApp() { }
    protected void destroyApp(boolean unconditional) { }
}
