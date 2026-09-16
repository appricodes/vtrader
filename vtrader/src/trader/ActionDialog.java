package trader;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.dukascopy.api.IOrder;

// The window F10 opens once an action has been picked from the spoken menu. It is the same window
// for both actions, built from the same three parts: a focusable read-only description of what the
// action does, one row per parameter, and the button that arms it. Everything the two actions used
// to take from a constant is a control here, so the values are chosen per position instead of being
// compiled in.
//
// Accessibility: each control carries its own accessible name rather than relying on the label
// standing next to it, which SWT does not reliably expose on Windows. Tab order follows creation
// order, so the widgets are built in the order they are meant to be read: description, then
// parameters, then buttons.
class ActionDialog {
	static final int ACTION_GUARANTEE = 0;
	static final int ACTION_REVERSE = 1;
	// what the F10 menu speaks, and what the window is titled
	static final String[] ACTION_NAMES = { "Profit guarantee", "Reverse guard" };

	private static final String GUARANTEE_DESCRIPTION =
			"Profit guarantee. This puts a trailing stop loss on the position you selected, so a "
			+ "profit you already have cannot be given back.\n"
			+ "\n"
			+ "Instant mode starts straight away. The stop loss is placed the stop loss percent "
			+ "away from the current price, on the losing side, and from then on it follows the "
			+ "price every time the price moves in your favour. It only ever tightens, so it never "
			+ "moves back.\n"
			+ "\n"
			+ "Conditional mode changes nothing yet. It waits until the price reaches the take "
			+ "profit that was already set on the position, and only then places that stop and "
			+ "starts following the price.\n"
			+ "\n"
			+ "In both modes the real take profit is pushed fifty percent away from the price, far "
			+ "out of reach, so the broker cannot close the position at the old target and the "
			+ "trailing stop is what decides when you get out.\n"
			+ "\n"
			+ "That fifty percent is not a setting here, and it is not set once and then left alone. "
			+ "Every single time the stop loss moves, the take profit is pushed out to fifty percent "
			+ "of the price again. So it stays out of reach however far the price runs, and if it "
			+ "ever gets moved by hand on the platform, by accident, the next move of the stop loss "
			+ "puts it straight back.\n"
			+ "\n"
			+ "A very small stop loss percent can fall inside the smallest distance the broker "
			+ "accepts. If that happens it is announced once, and tried again after the retry "
			+ "pause, for as long as the position is open.\n"
			+ "\n"
			+ "Both percents are a percent of the price divided by the leverage, the same way the "
			+ "stop loss and take profit percents on key 3 and key 4 work.";

	private static final String REVERSE_DESCRIPTION =
			"Reverse guard. This watches the position you selected and does nothing at all while it "
			+ "is doing well.\n"
			+ "\n"
			+ "If the position goes under water by the trigger percent, an opposite position of "
			+ "exactly the same size is opened, so the loss stops growing. From that moment on, "
			+ "whatever one side loses the other side gains.\n"
			+ "\n"
			+ "At the same moment the stop loss and the take profit of both positions are pushed "
			+ "out to the lock percent away from the price, so neither side closes on its own and "
			+ "the pair stays balanced. The opposite position is opened first: if the broker "
			+ "refuses it, your position is left exactly as it was.\n"
			+ "\n"
			+ "This happens once and once only.\n"
			+ "\n"
			+ "Later, when one of the two sides does close, the stop loss of the side still open is "
			+ "moved to the survivor stop percent of the stop loss distance the original position "
			+ "had before this guard was set, measured from the price the other side closed at.\n"
			+ "\n"
			+ "The slippage percent is how far away from the price you asked for the broker may "
			+ "still fill the opposite position.\n"
			+ "\n"
			+ "Warning: if the trigger percent is at or beyond the stop loss the position already "
			+ "has, that stop loss closes the position first and the guard never fires.\n"
			+ "\n"
			+ "Every percent below except the survivor stop percent is a percent of the price "
			+ "divided by the leverage, the same way the stop loss and take profit percents on key "
			+ "3 and key 4 work. The survivor stop percent is a share of a distance, so the "
			+ "leverage does not come into it.";

	private final VoiceMenu menu;
	private final int action;
	private final IOrder order;
	private final Shell shell;

	// profit guarantee
	private Combo modeCombo;
	private Combo guaranteeSlCombo;
	private Text retryText;
	// reverse guard
	private Combo triggerCombo;
	private Combo lockCombo;
	private Combo slippageCombo;
	private Combo survivorCombo;

	ActionDialog(Shell parent, VoiceMenu menu, int action, IOrder order) {
		this.menu = menu;
		this.action = action;
		this.order = order;
		this.shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
	}

	void open() {
		// the title is the first thing a screen reader announces when the window comes up, so it
		// names the position as well as the action - the window is about one position only
		shell.setText(String.format("%s - %s order %s",
				ACTION_NAMES[action],
				order.isLong() ? "buy" : "sell",
				order.getLabel()));
		shell.setLayout(new GridLayout(2, false));

		Text description = new Text(shell,
				SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
		// the constants are written with plain newlines; an SWT Text wants the platform's own
		// delimiter, and this project is built for Mac as well as Windows
		description.setText((action == ACTION_GUARANTEE ? GUARANTEE_DESCRIPTION : REVERSE_DESCRIPTION)
				.replace("\n", Text.DELIMITER));
		GridData descriptionData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
		descriptionData.widthHint = 520;
		descriptionData.heightHint = 260;
		description.setLayoutData(descriptionData);
		nameControl(description, "What this does. Read only.");

		if (action == ACTION_GUARANTEE)
			buildGuaranteeParameters();
		else
			buildReverseParameters();

		Composite buttons = new Composite(shell, SWT.NONE);
		buttons.setLayout(new GridLayout(2, false));
		buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false, 2, 1));

		Button run = new Button(buttons, SWT.PUSH);
		run.setText(action == ACTION_GUARANTEE ? "Arm profit guarantee" : "Arm reverse guard");
		run.addListener(SWT.Selection, e -> run());

		Button cancel = new Button(buttons, SWT.PUSH);
		cancel.setText("Cancel");
		cancel.addListener(SWT.Selection, e -> shell.close());

		// deliberately no default button: this window arms a position, and enter pressed while a
		// combo or the retry field has the focus must not be what does it. The button has to be
		// reached and pressed on purpose.
		// escape closes, as it does everywhere else in this program
		shell.addListener(SWT.Traverse, e -> {
			if (e.detail == SWT.TRAVERSE_ESCAPE) {
				e.doit = false;
				shell.close();
			}
		});
		// whichever way the window goes away, the key field behind it has to get the focus back, or
		// none of the function keys work any more
		shell.addListener(SWT.Dispose, e -> menu.focusKeyField());

		shell.pack();
		shell.open();
		// the description first, so the explanation is what gets read out
		description.setFocus();
	}

	private void buildGuaranteeParameters() {
		modeCombo = addChoice("Mode", "instant starts now, conditional waits for the take profit",
				new String[] { "Instant", "Conditional" }, menu.guaranteeMode);
		guaranteeSlCombo = addPercent("Stop loss percent", "how far behind the price the stop follows",
				smallPercents(), menu.guaranteeSlPercent);
		// no take profit row on purpose: it is fixed, and the description says why
		retryText = addNumber("Retry pause in seconds", "after the broker refuses a stop loss",
				formatNumber(menu.guaranteeRetryMs / 1000.0));
	}

	private void buildReverseParameters() {
		triggerCombo = addPercent("Trigger percent", "how far under water the position must go",
				smallPercents(), menu.reverseTriggerPercent);
		lockCombo = addPercent("Lock percent", "where the stop loss and take profit of both sides go",
				largePercents(), menu.reverseLockPercent);
		slippageCombo = addPercent("Slippage percent", "allowed when opening the opposite position",
				slippagePercents(), menu.reverseSlippagePercent);
		survivorCombo = addPercent("Survivor stop percent",
				"the share of the original stop loss distance the surviving side keeps",
				largePercents(), menu.reverseSurvivorStopPercent);
	}

	// one label plus one read only combo. Read only so the arrow keys pick a value and nothing the
	// user types can end up in it. The label stays short enough to keep the window a sane width; the
	// hint is what the screen reader reads out after the name, and it is in the description too.
	private Combo addChoice(String label, String hint, String[] items, int selection) {
		Label caption = new Label(shell, SWT.NONE);
		caption.setText(label + ":");
		Combo combo = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);
		combo.setItems(items);
		combo.select(Math.max(0, Math.min(items.length - 1, selection)));
		combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		// a combo swallows escape, so without this the key does nothing at all while one has the
		// focus and the only way out is to find the cancel button. The list check is there to leave
		// escape to the dropped-down list where Windows gives it to the list first; where it does
		// not, the window closes, which costs nothing - this window arms nothing until its button
		// is pressed.
		combo.addListener(SWT.KeyDown, e -> {
			if (e.keyCode == SWT.ESC && !combo.getListVisible())
				shell.close();
		});
		nameControl(combo, label + ", " + hint);
		return combo;
	}

	private Combo addPercent(String label, String hint, List<Double> choices, double current) {
		List<Double> values = new ArrayList<>(choices);
		insertValue(values, current);
		String[] items = new String[values.size()];
		int selected = 0;
		for (int i = 0; i < values.size(); i++) {
			items[i] = formatNumber(values.get(i));
			if (values.get(i) == current)
				selected = i;
		}
		return addChoice(label, hint, items, selected);
	}

	private Text addNumber(String label, String hint, String value) {
		Label caption = new Label(shell, SWT.NONE);
		caption.setText(label + ":");
		Text text = new Text(shell, SWT.SINGLE | SWT.BORDER);
		text.setText(value);
		text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		nameControl(text, label + ", " + hint);
		return text;
	}

	// SWT on Windows does not reliably hand a screen reader the label standing next to a control,
	// so every control says its own name
	private void nameControl(Control control, String name) {
		control.getAccessible().addAccessibleListener(new AccessibleAdapter() {
			@Override
			public void getName(AccessibleEvent e) {
				e.result = name;
			}
		});
	}

	private void run() {
		// the window can sit open for as long as the user wants to read it, and the position may
		// well be gone by the time the button is pressed
		if (order.getState() != IOrder.State.FILLED) {
			menu.speak("Position " + order.getLabel() + " is no longer open. Nothing was armed.");
			shell.close();
			return;
		}
		if (action == ACTION_GUARANTEE) {
			// a pause of zero would put a refused stop back in front of the broker on very nearly
			// every tick, so the field has to be a real number and at least one second
			double retrySeconds = parseNumber(retryText.getText(), -1);
			if (retrySeconds < 1) {
				menu.speak("The retry pause must be a number of seconds, one or more."
						+ " Correct it and press the button again.");
				retryText.setFocus();
				return;
			}
			menu.guaranteeMode = modeCombo.getSelectionIndex();
			menu.guaranteeSlPercent = comboValue(guaranteeSlCombo, menu.guaranteeSlPercent);
			menu.guaranteeRetryMs = Math.round(retrySeconds * 1000);
			shell.close();
			menu.armProfitGuarantee(order, menu.guaranteeMode, menu.guaranteeSlPercent,
					menu.guaranteeRetryMs);
		}
		else {
			menu.reverseTriggerPercent = comboValue(triggerCombo, menu.reverseTriggerPercent);
			menu.reverseLockPercent = comboValue(lockCombo, menu.reverseLockPercent);
			menu.reverseSlippagePercent = comboValue(slippageCombo, menu.reverseSlippagePercent);
			menu.reverseSurvivorStopPercent = comboValue(survivorCombo, menu.reverseSurvivorStopPercent);
			shell.close();
			menu.armReverseGuard(order, menu.reverseTriggerPercent, menu.reverseLockPercent,
					menu.reverseSlippagePercent, menu.reverseSurvivorStopPercent);
		}
	}

	// the combos are read only and every entry in them is a number, so the fallback should never be
	// needed; if it ever is, the value already in force is the safe thing to fall back on - a zero
	// would put a stop loss or a target exactly on the price
	private double comboValue(Combo combo, double fallback) {
		return parseNumber(combo.getText(), fallback);
	}

	private double parseNumber(String text, double fallback) {
		try {
			return Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	// "2" rather than "2.0": the combo is read out loud, and the trailing zero is just noise
	private static String formatNumber(double value) {
		if (value == Math.rint(value))
			return String.valueOf((long) value);
		return String.valueOf(Math.round(value * 1000) / 1000.0);
	}

	// keeps whatever is currently in force in the list, wherever it belongs, so the combo never
	// opens on a value that is not the one actually set
	private static void insertValue(List<Double> values, double value) {
		for (int i = 0; i < values.size(); i++) {
			if (values.get(i) == value)
				return;
			if (values.get(i) > value) {
				values.add(i, value);
				return;
			}
		}
		values.add(value);
	}

	private static List<Double> range(double from, double to, double step) {
		List<Double> values = new ArrayList<>();
		for (double v = from; v <= to + step / 2; v += step)
			values.add(Math.round(v * 1000) / 1000.0);
		return values;
	}

	// distances measured against a position: fine near zero, where the choice actually matters
	private static List<Double> smallPercents() {
		List<Double> values = range(0.5, 10, 0.5);
		values.addAll(range(12, 30, 2));
		return values;
	}

	// levels meant to be out of reach, or shares of a distance
	private static List<Double> largePercents() {
		List<Double> values = range(5, 100, 5);
		values.addAll(range(125, 200, 25));
		return values;
	}

	private static List<Double> slippagePercents() {
		List<Double> values = new ArrayList<>();
		for (double v : new double[] { 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1 })
			values.add(v);
		return values;
	}
}
