/*
Copyright (c) 2025 Arman Jussupgaliyev
*/
package emulator.lcdui;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Graphics;

public interface IListImpl extends Choice, IScreenImpl {

	void drawScrollBar(Graphics graphics);

	Object automationLock();
	Object automationRowIdentity(int index);
	boolean automationSelect(Object identity, int index, boolean selected);
}
