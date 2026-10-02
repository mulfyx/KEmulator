package fixtures;

import emulator.Permission;
import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;

/** Small, deterministic screens for the agent's public workflow. */
public final class AgentContractFixtureMidlet extends MIDlet implements CommandListener {
    private final Command kept = new Command("Kept", Command.SCREEN, 1);
    private final Command removed = new Command("Removed", Command.SCREEN, 2);
    private final Command mutate = new Command("Mutate commands", Command.SCREEN, 3);
    private final Command replacement = new Command("Replacement", Command.SCREEN, 2);
    private final Command rows = new Command("List", Command.SCREEN, 4);
    private final Command changeRows = new Command("Change rows", Command.SCREEN, 1);
    private final Command changeChoice = new Command("Change choices", Command.SCREEN, 5);
    private final Command colors = new Command("Colors", Command.SCREEN, 6);
    private final Command permission = new Command("Input permission", Command.SCREEN, 7);
    private final Command slow = new Command("Slow input", Command.SCREEN, 8);
    private final Command showAlert = new Command("Alert", Command.SCREEN, 9);
    private Form form;
    private List list;
    private ChoiceGroup choice;
    private int keptCount;

    protected void startApp() {
        if (form == null) {
            form = new Form("Agent form");
            form.append(new TextField("A", "first", 32, TextField.ANY));
            form.append(new TextField("B", "second", 32, TextField.ANY));
            form.append(new TextField("Digits", "12", 4, TextField.NUMERIC));
            choice = new ChoiceGroup("Options", Choice.MULTIPLE);
            choice.append("old first", null);
            choice.append("old second", null);
            form.append(choice);
            form.addCommand(kept);
            form.addCommand(removed);
            form.addCommand(mutate);
            form.addCommand(rows);
            form.addCommand(changeChoice);
            form.addCommand(colors);
            form.addCommand(permission);
            form.addCommand(slow);
            form.addCommand(showAlert);
            form.setCommandListener(this);
        }
        Display.getDisplay(this).setCurrent(form);
    }

    protected void pauseApp() { }
    protected void destroyApp(boolean unconditional) { }

    public void commandAction(Command command, Displayable owner) {
        if (command == kept) {
            form.setTitle("Kept " + (++keptCount));
        } else if (command == removed) {
            form.setTitle("Removed activated");
        } else if (command == replacement) {
            form.setTitle("Replacement activated");
        } else if (command == mutate) {
            Display.getDisplay(this).callSerially(new Runnable() {
                public void run() {
                    form.removeCommand(removed);
                    form.addCommand(replacement);
                    form.setTitle("Commands changed");
                }
            });
        } else if (command == changeChoice) {
            choice.delete(0);
            choice.insert(0, "new first", null);
            form.setTitle("Choices changed");
        } else if (command == rows) {
            list = new List("Agent list", List.IMPLICIT);
            list.append("old first", null);
            list.append("old second", null);
            list.addCommand(changeRows);
            list.setCommandListener(this);
            Display.getDisplay(this).setCurrent(list);
        } else if (command == changeRows) {
            Display.getDisplay(this).callSerially(new Runnable() {
                public void run() {
                    list.delete(0);
                    list.insert(0, "new first", null);
                    list.setTitle("Rows changed");
                }
            });
        } else if (command == List.SELECT_COMMAND && owner == list) {
            list.setTitle("Activated " + list.getString(list.getSelectedIndex()));
        } else if (command == colors) {
            Display.getDisplay(this).setCurrent(new ColorCanvas(false));
        } else if (command == permission || command == slow) {
            Display.getDisplay(this).setCurrent(new InputCanvas(command == permission));
        } else if (command == showAlert) {
            Alert alert = new Alert("Agent alert", "Agent alert body", null, AlertType.INFO);
            alert.setTimeout(60000);
            alert.setIndicator(new Gauge(null, false, 10, 3));
            alert.setTicker(new Ticker("Agent alert ticker"));
            Display.getDisplay(this).setCurrent(alert, form);
        }
    }

    private final class ColorCanvas extends Canvas {
        private final boolean blue;
        private boolean firstPaint = true;
        ColorCanvas(boolean blue) {
            this.blue = blue;
            setFullScreenMode(true);
            setTitle(blue ? "Canvas B" : "Canvas A");
        }
        protected void paint(Graphics g) {
            if (blue && firstPaint) {
                firstPaint = false;
                // Deliberately create a bounded new-screen-without-frame interval.
                try { Thread.sleep(350L); } catch (InterruptedException ignored) { }
            }
            g.setColor(blue ? 0x0000FF : 0xFF0000);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        protected void keyPressed(int key) {
            if (blue) return;
            if (key == Canvas.KEY_NUM6 || key == Canvas.KEY_NUM7) {
                ProbeGameCanvas game = new ProbeGameCanvas(key == Canvas.KEY_NUM6);
                Display.getDisplay(AgentContractFixtureMidlet.this).setCurrent(game);
                game.drawFirstRegion();
            } else {
                Display.getDisplay(AgentContractFixtureMidlet.this).setCurrent(new ColorCanvas(true));
            }
        }
    }

    /** Default GameCanvas paint and partial flush must own their entire frame. */
    private final class ProbeGameCanvas extends javax.microedition.lcdui.game.GameCanvas {
        private final boolean flush;
        ProbeGameCanvas(boolean flush) {
            super(false);
            this.flush = flush;
            setFullScreenMode(true);
            setTitle(flush ? "Game partial flush" : "Game repaint");
        }
        void drawFirstRegion() {
            Graphics g = getGraphics();
            g.setColor(0x0000FF);
            g.fillRect(20, 20, 50, 50);
            if (flush) {
                flushGraphics(20, 20, 50, 50);
            } else {
                repaint();
                serviceRepaints();
            }
        }
        protected void keyPressed(int key) {
            Graphics g = getGraphics();
            g.setColor(0x00FF00);
            g.fillRect(0, 0, getWidth(), getHeight());
            // Applications may request an ordinary repaint instead of flush.
            repaint();
            serviceRepaints();
            setTitle("Game repaint changed");
        }
    }

    private final class InputCanvas extends Canvas {
        private final boolean ask;
        InputCanvas(boolean ask) {
            this.ask = ask;
            setTitle(ask ? "Input permission ready" : "Slow input ready");
        }
        protected void paint(Graphics g) {
            g.setColor(0xFFFFFF);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        protected void keyPressed(int key) {
            System.out.println("AGENT keyPressed entered " + key);
            if (ask) {
                try { Permission.checkPermission("media.camera"); }
                catch (SecurityException denied) { }
            } else {
                try { Thread.sleep(2000L); } catch (InterruptedException ignored) { }
            }
            setTitle("Input pressed completed");
            System.out.println("AGENT keyPressed completed " + key);
            repaint();
        }
        protected void keyReleased(int key) {
            setTitle("Input released");
            System.out.println("AGENT keyReleased " + key);
            repaint();
        }
    }
}
