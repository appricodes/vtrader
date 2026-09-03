package trader;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import javax.swing.JFrame;
import javax.swing.JTextField;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;

import com.dukascopy.api.IBar;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IReportPosition;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;
import com.github.psambit9791.jdsp.signal.peaks.FindPeak;
import com.github.psambit9791.jdsp.signal.peaks.Peak;


class VoiceMenu  implements APICallback{
	private static final int TYPE_NONE = 0;
	private static final int TYPE_BUY = 1;
	private static final int TYPE_SELL = 2;
	private static final int TYPE_HEDGE = 3;
	private static final int MAX_DAYS_BACK = 60; // ~2 months

	private static VoiceMenu instance;

	private List<MyInstrument> instruments ;
	private int selectedInstrument = 0;
	private String op = "instrument";
	private int openType = TYPE_NONE;
	boolean isDirectionSelected;
	private ITick initTick; // last tick before user presses F1
	private double openPrice;
	private int idx = 0;
	private int dayOffset = 0; // days back from today, used by F7 day browsing
	private DayStats currentDayStats; // stats for the day currently selected under F7
	private boolean shiftDown = false; // tracked manually: modifier bit on arrow-key events is unreliable on some setups
	private boolean shiftUsedAsModifier = false; // true once Shift was combined with another key, to distinguish a plain Shift tap from Shift+arrow
	private int rate = 25; // speech rate

	// F4 stop loss / take profit update: target price selection state
	private IOrder pendingUpdateOrder; // order selected via F2, targeted by F4
	private ITick updateInitTick; // tick captured at F4 press; fixed baseline for target stepping
	private int updateTargetLevel = 0; // 0 = "Now" (immediate); >0 = ask + level*step; <0 = bid + level*step
	private double updateTargetPrice; // computed target price when updateTargetLevel != 0

	// armed conditional SL/TP updates, one per order, watched against live ticks in checkPendingConditionalUpdate
	private List<PendingConditionalUpdate> pendingConditionalUpdates = new ArrayList<>();
	private List<IOrder> openOrders;
	private List<IReportPosition>  closedOrders = new ArrayList<IReportPosition>();
	private List<String> textList = new ArrayList<String>();
	// full-precision counterpart of textList, spoken when a nav key is pressed with shift held
	private List<String> textListFull = new ArrayList<String>();

	class CloseOrderTask implements Callable<Boolean> {
		IOrder order;

		public CloseOrderTask(IOrder order) {
			this.order = order;
		}

		@Override
		public Boolean call(){
			try {
				order.close();
				speak("Closing position");
				return true;
			} catch (JFException e) {
				speak("Error");
				e.printStackTrace();
				return false;
			}
		}
	}

	class PendingConditionalUpdate {
		final IOrder order;
		final double targetPrice;
		final boolean above; // true: trigger when ask rises to target; false: trigger when bid falls to target

		PendingConditionalUpdate(IOrder order, double targetPrice, boolean above) {
			this.order = order;
			this.targetPrice = targetPrice;
			this.above = above;
		}
	}

	class UpdateOrderTask implements Callable<Boolean> {
		IOrder order;

		public UpdateOrderTask(IOrder order) {
			this.order = order;
		}

		@Override
		public Boolean call(){
			ITick lastTick = null; 
			try {
				lastTick = MyStrategy.getContext().getHistory().getLastTick(order.getInstrument());
			}catch (Exception e) {
				e.printStackTrace();
				Main.speak("Error");
				return false;
			}

			double sl = lastTick.getAsk() * instruments.get(selectedInstrument).slp / 100.0 / instruments.get(selectedInstrument).instrument.getLeverageUse();
			double tp = lastTick.getAsk() * instruments.get(selectedInstrument).tpp / 100.0 / instruments.get(selectedInstrument).instrument.getLeverageUse();
			
			double slp;
			double tpp;

			if (order.isLong()) {
				slp = lastTick.getAsk() - sl;
				tpp = lastTick.getBid() +  tp;
			} 
			else {
				slp = lastTick.getBid() + sl;
				tpp = lastTick.getAsk() - tp;
			}
			double pip = instruments.get(selectedInstrument).instrument.getPipValue();
			slp = Math.round(slp /pip)*pip;
			tpp = Math.round(tpp /pip)*pip;
			speak("Updating SL and TP");
			try {
				order.setStopLossPrice(slp);
				MyUtils.sleep(2000);
				order.setTakeProfitPrice(tpp);
				return true;
			} catch (JFException e) {
				e.printStackTrace();
				speak("Error");
				return false;
			}

		}
	}






	public VoiceMenu() {
		instance = this;
		SineWaveGenerator generator = new SineWaveGenerator();
		//generator.play();
		MyInstrument.load();
		instruments = new ArrayList<>(MyInstrument.getInstruments().values());
		for (int i=0; i<instruments.size(); i++) {
			System.out.println(instruments.get(i).name);
		}
	}
	private String formatPrice(double price) {
		return formatPrice(price, false, null);
	}
	private String formatPrice(double price, boolean shorter, MyInstrument instrument) {
		int skipDigits = 2;
		int digits = 3;
		if (instrument != null) {
			if (instrument.dName.equals("USA500.IDX/USD"))
				skipDigits = 1;
		}
		String s;
		
		if (shorter) {
			s = String.valueOf(price + 0.0000001);
			// keep only digits
			s = s.replaceFirst("\\D", "");
			String re = String.format("^(\\d{%d})(\\d{%d}).*", skipDigits, digits);
			s = s.replaceFirst(re, "$2");
			// add space
			s = s.replaceFirst("^(\\d*)(\\d{2})$", "$1 $2");
			s = s.replaceFirst("^(\\d*)(\\d{2})\\.", "$1 $2.");
		}
		else {
			s = String.valueOf(price);
			//s = s.replaceFirst("(\\d)(\\d\\d\\d)\\.", "$1, $2.");
		}
		
		return s;
	}
	private String del_formatPrice(double price, boolean shorter) {

		// scale the price to a higher range if necessary
		if (shorter) {
			if (price < 50)
				price *= 10000;
			else if (price < 500)
				price *= 100;
		}
		
		String s;
		
		if (shorter) { 
			//s = s.replaceFirst(".*(\\d)(\\d\\d)\\.(\\d)(\\d).*", "$1 $2 dot $3");
			s = String.valueOf(price + 0.00001);
			s = s.replaceFirst(".*(\\d)(\\d{2})\\.(\\d)(\\d).*", "$1 $2 dot $3");
		}
		else {
			s = String.valueOf(price);
			s = s.replaceFirst("(\\d)(\\d\\d\\d)\\.", "$1, $2.");
		}
		// is it a round number?
		s = s.replaceFirst("\\.0+$", "");
		return s;
	}
	private double increasePrice(double price, int speed) {
		long p = Math.round(price * 1e10);
		int m = (int) Math.ceil(Math.log10(p))-2;
		if (Math.round(Math.log10(p)) == Math.log10(p) && speed > 0)
			m += 1;
		long d = (long)Math.pow(10, m);
		p += d * speed;

		p = Math.round(p / d) * d;

		return p / 1e10;
	}
	private ITick getLastTick(Instrument instrument) {
		if (MyStrategy.getContext() == null) {
			speak("Please wait.");
			return null;
		}

		IHistory history = MyStrategy.getContext().getHistory();
		ITick tick = null;
		try {
			tick = history.getLastTick(instrument);
			} catch (JFException e) {
			speak("Error getting price");
		}
		return tick;
	}
	private double getPrice(Instrument instrument, boolean bid) {
		ITick tick = getLastTick(instrument);
		if (bid)
			return tick.getBid();
		else
			return tick.getAsk();
	}
	private int getLabelId() {
		File file = new File(Main.baseDir, "my_config" + Main.separator + "last_label_id.txt");
		int id = Integer.parseInt(MyUtils.fileGetContents(file));
		id += 1;
		MyUtils.filePutContents(file, id + "");
		return id;
	}
	private void speak(String text) {
		Main.speak(text, rate);
	}
	public void start() {
		speak("Connecting, please wait.");
		JFrame frame = new JFrame("Voice Trader");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(300, 100);

		// Create a text field to capture focus
		JTextField textField = new JTextField();
		textField.setEditable(false);
		frame.add(textField);

		// Add a KeyListener to the text field
		textField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
					shiftDown = false;
					// a plain tap of Shift (not combined with another key) switches to messages, as before
					if (!shiftUsedAsModifier) {
						op = "messages";
						idx = MyStrategy.messages.size() - 1;
						reportMessage();
					}
					shiftUsedAsModifier = false;
				}
			}
			@Override
			public void keyPressed(KeyEvent e) {
				//public void keyReleased(KeyEvent e) {
				switch(e.getKeyCode()) {
				case  KeyEvent.VK_UP:
				case  KeyEvent.VK_DOWN:
				case  KeyEvent.VK_PAGE_UP:
				case  KeyEvent.VK_PAGE_DOWN:
				case  KeyEvent.VK_HOME:
				case  KeyEvent.VK_END: {
					// shift held while navigating means "announce the full price instead of the short one"
					boolean shift = shiftDown || e.isShiftDown();
					if (shift)
						shiftUsedAsModifier = true;
					int step;
					switch (e.getKeyCode()) {
					case KeyEvent.VK_UP:        step = 1; break;
					case KeyEvent.VK_DOWN:      step = -1; break;
					case KeyEvent.VK_PAGE_UP:   step = 10; break;
					case KeyEvent.VK_PAGE_DOWN: step = -10; break;
					case KeyEvent.VK_HOME:      step = 1000000000; break;
					default:                    step = -1000000000; break;
					}
					processCursor(step, shift);
					break;
				}
				case  KeyEvent.VK_1:
					// instrument selection
					op = "instrument";
					reportInstrument();
					break;
				case  KeyEvent.VK_2:
					reportPrice(TYPE_SELL, false, instruments.get(selectedInstrument));
					break;
				case  KeyEvent.VK_LEFT:
					if (op.equals("open") && !isDirectionSelected)
						adjustOpenPrice(-1);
					else if (op.equals("days"))
						speakDayMin();
					else if (op.equals("update_sl_tp"))
						adjustUpdateTarget(-1);
					else {
						boolean shift = shiftDown || e.isShiftDown();
						if (shift)
							shiftUsedAsModifier = true;
						reportPrice(TYPE_SELL, !shift, instruments.get(selectedInstrument));
					}
					break;
				case  KeyEvent.VK_RIGHT:
					if (op.equals("open") && !isDirectionSelected)
						adjustOpenPrice(1);
					else if (op.equals("days"))
						speakDayMax();
					else if (op.equals("update_sl_tp"))
						adjustUpdateTarget(1);
					else {
						boolean shift = shiftDown || e.isShiftDown();
						if (shift)
							shiftUsedAsModifier = true;
						reportPrice(TYPE_BUY, !shift, instruments.get(selectedInstrument));
					}
					break;
				case  KeyEvent.VK_3:
					op = "slp";
					reportSLP();
					break;
				case  KeyEvent.VK_4:
					op = "tpp";
					reportTPP();
					break;
				case  KeyEvent.VK_5:
					op = "quantity";
					reportQuantity();
					break;
				case  KeyEvent.VK_6:
					op = "risk";
					reportRisk();
					break;
				case  KeyEvent.VK_F2:
					reportOpenOrders();
					break;
				case  KeyEvent.VK_F3:
					if (op.equals("open_orders")) {
						if (openOrders.isEmpty()) {
							speak("No open positions");
							break;
						}
						op = "close_order";
						IOrder order = openOrders.get(idx);
						speak(String.format(
								"Close %s order %s with profit: %s? Press space to confirm.",
								(order.isLong()) ? "buy" : "sell",
										order.getLabel(),
										formatPrice(order.getProfitLossInAccountCurrency())
								));
					}
					break;
				case  KeyEvent.VK_F4:
					if (op.equals("open_orders")) {
						if (openOrders.isEmpty()) {
							speak("No open positions");
							break;
						}
						op = "update_sl_tp";
						IOrder order = openOrders.get(idx);
						pendingUpdateOrder = order;
						updateInitTick = getLastTick(order.getInstrument());
						updateTargetLevel = 0;
						speak(String.format(
								"Update stop loss and take profit of %s order %s, to: %s%%, and %s%%? Press enter to confirm now, or use left and right to set a target price.",
								(order.isLong()) ? "buy" : "sell",
										order.getLabel(),
										formatPrice(instruments.get(selectedInstrument).slp),
										formatPrice(instruments.get(selectedInstrument).tpp)
								));
					}
					break;
				case  KeyEvent.VK_Q:
					reportClosedOrders();
					break;
				case  KeyEvent.VK_W:
					reportAccount();
					break;
				case  KeyEvent.VK_F1:
					op = "open";
					openType = TYPE_NONE;
					initTick = getLastTick(instruments.get(selectedInstrument).getInstrument());
					openPrice = (initTick.getAsk() + initTick.getBid()) /2;
					isDirectionSelected = false;
					
					speak("Open new position");
					break;
				case  KeyEvent.VK_SHIFT:
					shiftDown = true;
					break;
				case  KeyEvent.VK_CAPS_LOCK:
					processRate();
					break;
				case  KeyEvent.VK_SPACE:
				case  KeyEvent.VK_ENTER:
					processConfirm();
					op = "";
					break;
				case  KeyEvent.VK_A:
					op = "voice";
					speak("Voice");
					break;
				case  KeyEvent.VK_ESCAPE:
					op = "";
					speak("Cancelled");
					break;
				case  KeyEvent.VK_CONTROL:
					speak("");
					break;
				case  KeyEvent.VK_F5:
					reportHistory(Period.FIVE_MINS);
					break;
				case  KeyEvent.VK_F6:
					reportHistory(Period.ONE_HOUR);
					break;
				case  KeyEvent.VK_F7:
					if (MyStrategy.getContext() == null) {
						speak("Please wait");
						break;
					}
					op = "days";
					dayOffset = 0;
					reportDay();
					break;
				case  KeyEvent.VK_F8:
					reportPeaks();
					break;
				case  KeyEvent.VK_F9:
					if (op.equals("open")) {
						openType = TYPE_HEDGE;
						isDirectionSelected = false;
						speak("Hedging mode");
					}
					break;
				case  KeyEvent.VK_F12:
					speak(MyUtils.formatTime(System.currentTimeMillis()));
					break;
				}
			}
		});

		frame.setVisible(true);

	}
	private void processCursor(int direction, boolean shift) {
		// instrument sellection
		if (op.equals("instrument")) {
			selectedInstrument += direction;
			if (selectedInstrument  >= instruments.size())
				selectedInstrument  = instruments.size()-1;
			if (selectedInstrument  < 0)
				selectedInstrument  = 0;
			reportInstrument();
		}
		else if (op.equals("slp")) {
			double slp = instruments.get(selectedInstrument).slp;
			double x = slp + direction / 10.0;
			if (x < 5)
				direction *= 2;
			else
				direction *= 5;
			if (x >= 10)
				direction *= 2;
			slp = Math.round(slp*10 + direction) / 10.0;
			slp = Math.max(1, Math.min(100, slp));
			instruments.get(selectedInstrument).slp  = slp;
			
			speak(formatPrice(instruments.get(selectedInstrument).slp));
		}
		else if (op.equals("tpp")) {
			double tpp = instruments.get(selectedInstrument).tpp;
			double x = tpp + direction / 10.0;
			if (x < 5)
				direction *= 2;
			else
				direction *= 5;
			if (x >= 10)
				direction *= 2;
			tpp = Math.round(tpp*10 + direction) / 10.0;
			tpp = Math.max(1, Math.min(100, tpp));
			instruments.get(selectedInstrument).tpp  = tpp;
			
			speak(formatPrice(instruments.get(selectedInstrument).tpp));

		}
		else if (op.equals("quantity")) {
			MyInstrument instrument = instruments.get(selectedInstrument);
			if (instrument.quantity  < 10)
				direction *= 10;
			instrument.quantity = (int)increasePrice(instrument.quantity, direction);
			if (instrument.quantity < 1)
				instrument.quantity = 1;
			// keep the risk percent in sync with the manually-adjusted quantity, when price data is available
			if (MyStrategy.getContext() != null) {
				ITick tick = getLastTick(instrument.getInstrument());
				double leverage = instrument.instrument.getLeverageUse();
				if (tick != null && leverage > 0)
					instrument.percent = computeRiskPercent(instrument, tick.getAsk(), leverage);
			}
			speak(String.format("%d", instrument.quantity));
		}
		else if (op.equals("risk")) {
			if (MyStrategy.getContext() == null) {
				speak("Please wait.");
			}
			else {
				MyInstrument instrument = instruments.get(selectedInstrument);
				ITick tick = getLastTick(instrument.getInstrument());
				double leverage = instrument.instrument.getLeverageUse();
				if (tick == null || leverage <= 0) {
					speak("Please wait.");
				}
				else {
					double askPrice = tick.getAsk();
					// percent is stored directly on the instrument now, not re-derived from quantity
					// each time - that round trip used to get stuck on high-priced instruments where
					// rounding quantity to an int couldn't tell two nearby percents apart.
					if (instrument.percent < 0)
						instrument.percent = computeRiskPercent(instrument, askPrice, leverage);
					int percent = instrument.percent + direction * 5;
					percent = Math.max(5, Math.min(70, percent));
					instrument.percent = percent;
					double balance = MyStrategy.getContext().getAccount().getBalance();
					// percent of balance used as margin; quantity = margin * leverage / price
					int newQuantity = (int) Math.round(balance * percent / 100.0 * leverage / askPrice);
					if (newQuantity < 1)
						newQuantity = 1;
					instrument.quantity = newQuantity;
					speak(String.format("Risk %d%%, quantity %d", percent, instrument.quantity));
				}
			}
		}
		else if (op.equals("open_orders")) {
			idx += direction;
			if (idx >= openOrders.size())
				idx = openOrders.size() -1;
			if (idx <0) 
				idx = 0;
			IOrder order = openOrders.get(idx);
			double profit = Math.round((order.getProfitLossInAccountCurrency() + 2 * order.getCommission()) * 100) / 100.0;
			speak(String.format(
					"%s %s, profit: %s, %s, %d x, %s, open price: %s, time: %s",
					order.getFillHistory().isEmpty() ? "*" : "",
					order.getLabel(),
					formatPrice(profit),
					(order.isLong()) ? "buy" : "sell",
							Math.round(order.getAmount() * 1000000),
							order.getInstrument().getName(),
							formatPrice(order.getOpenPrice()),
							MyUtils.formatTime(order.getCreationTime())
					));

		}
		else if (op.equals("closed_orders")) {
			idx += direction;
			if (idx >= closedOrders.size())
				idx = closedOrders.size() -1;
			if (idx <0) 
				idx = 0;
			IReportPosition position = closedOrders.get(idx);
			// exchange rate to account currency
			double r = 1;
			try {
				r = MyStrategy.getContext().getUtils().getRate(
						position.getProfitLoss().getJFCurrency(),
						MyStrategy.getContext().getAccount().getAccountCurrency() 
				);
			} catch (JFException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			double profit = Math.round((position.getProfitLoss().getAmount() * r + position.getCommission().getAmount()) *100) / 100.0;
			speak(String.format(
					"%s, profit: %s, %d x, %s, open price: %s, close price: %s, closing time: %s",
					MyUtils.formatDateTimeShort(position.getOpenTime()),
					formatPrice(profit),
					Math.round(position.getAmount()),
					position.getInstrument().getName(),
					formatPrice(position.getOpenPrice()),
					formatPrice(position.getClosePrice()),
					MyUtils.formatTime(position.getCloseTime())
					));
		}
		else if (op.equals("days")) {
			int step = -direction;
			DayStats stats;
			boolean moved;
			do {
				int prevOffset = dayOffset;
				int candidate = dayOffset + step;
				candidate = Math.max(0, Math.min(MAX_DAYS_BACK, candidate));
				dayOffset = candidate;
				moved = dayOffset != prevOffset;
				stats = computeDayStats(dayOffset);
			} while (moved && stats.hasData && stats.minPrice == stats.maxPrice);
			currentDayStats = stats;
			speakDayStats(stats);
		}
		else if (op.equals("messages")) {
			idx += direction;
			if (idx < 0)
				idx = 0;
			if (idx >= MyStrategy.messages.size())
				idx = MyStrategy.messages.size() -1;
			reportMessage();
		}
		else if (op.equals("open")) {
			openType = (direction == 1) ? TYPE_BUY : TYPE_SELL;
			isDirectionSelected = true;
			reportOrderRequest();
		}
		else if (op.equals("voice")) {
			Main.selectNextVoice(direction);
		}
		else if (op.equals("text_list")) {
			idx += direction;
			if (idx < 0)
				idx = 0;
			if (idx >= textList.size())
				idx = textList.size() - 1;
			if (shift && idx < textListFull.size())
				speak(textListFull.get(idx));
			else
				speak(textList.get(idx));
		}
		
	}
	private void reportInstrument() {
		speak(String.format(
				"%s (%s)",
				instruments.get(selectedInstrument).name,
				instruments.get(selectedInstrument).instrument.isTradable() ? "Tradable" : "non tradable"
				));
	}
	private void reportPrice(int type, boolean shorter, MyInstrument instrument) {
		if (MyStrategy.getContext() != null) {
			double price = getPrice(instruments.get(selectedInstrument).getInstrument(), type == TYPE_SELL);
			speak(formatPrice(price, shorter, instrument));
		}
		else {
			speak("Please wait.");
		}

	}
	private void reportSLP() {
		double slp = instruments.get(selectedInstrument).slp;
		speak(String.format("Stop loss %s%%", formatPrice(slp)));
	}
	private void reportTPP() {
		double tpp = instruments.get(selectedInstrument).tpp;
		speak(String.format("Take profit %s%%", formatPrice(tpp)));
	}
	private void reportQuantity() {
		speak(String.format("Quantity %d", instruments.get(selectedInstrument).quantity));
	}
	// derives the balance-usage percent (rounded to the nearest 5%, 5-70) that a quantity
	// corresponds to. Used to keep instrument.percent in sync whenever quantity is set some
	// other way (key 5, or the initial seed the first time key 6 is used).
	// quantity = (balance * percent/100) * leverage / askPrice, so percent is the inverse of that.
	private int computeRiskPercent(MyInstrument instrument, double askPrice, double leverage) {
		double balance = MyStrategy.getContext().getAccount().getBalance();
		if (balance <= 0 || askPrice <= 0 || leverage <= 0)
			return 5;
		double percent = instrument.quantity * askPrice / leverage / balance * 100.0;
		int rounded = (int) (Math.round(percent / 5.0) * 5);
		return Math.max(5, Math.min(70, rounded));
	}
	private void reportRisk() {
		MyInstrument instrument = instruments.get(selectedInstrument);
		if (instrument.percent < 0) {
			// not seeded yet: derive an initial value from the instrument's configured quantity
			if (MyStrategy.getContext() == null) {
				speak("Please wait.");
				return;
			}
			ITick tick = getLastTick(instrument.getInstrument());
			double leverage = instrument.instrument.getLeverageUse();
			if (tick == null || leverage <= 0) {
				speak("Please wait.");
				return;
			}
			instrument.percent = computeRiskPercent(instrument, tick.getAsk(), leverage);
		}
		speak(String.format("Risk %d%%, quantity %d", instrument.percent, instrument.quantity));
	}
	private void processRate() {
		rate += 20;
		if (rate > 90)
			rate = 25;
		String s = "Speech rate: Slow";
		if (rate > 40)
			s = "Speech rate: Normal";
		if (rate > 60)
			s = "Speech rate: Fast";
		if (rate > 80)
			s = "Speech rate: Faster";
		speak(s);
	}
	private void reportOrderRequest() {
		MyInstrument instrument = instruments.get(selectedInstrument);
		String direction = (openType == TYPE_BUY) ? "buy" : "sell"; 
		speak(String.format("%s, %d x %s, stop loss: %s%%, take profit: %s%%, Press space to confirm.",
				direction, 
				instrument.quantity,  instrument.name,
				formatPrice(instrument.slp), formatPrice(instrument.tpp) 
				));
	}
	private void processConfirm() {
		if (op.equals("open") ) {
			if (openType == TYPE_NONE) {
				speak("Please select buy or sell, by pressing up or down cursors.");
				return;
			}
			MyInstrument instrument = instruments.get(selectedInstrument);
			if (!instrument.instrument.isTradable()) {
				speak("non tradable");
				return;
			}
			int labelId  = getLabelId();
			// use market price if no price is set
			if (openPrice < initTick.getAsk() && openPrice > initTick.getBid())
				openPrice = 0;
			double slp = initTick.getAsk() * instrument.slp / 100.0 / instrument.instrument.getLeverageUse();
			double tpp = initTick.getAsk() * instrument.tpp / 100.0 / instrument.instrument.getLeverageUse();
			int direction;
			if (openType == TYPE_HEDGE)
				direction = Command.OPERATION_HEDGE;
			else if (openType == TYPE_BUY)
				direction = Command.OPERATION_BUY;
			else
				direction = Command.OPERATION_SELL;
			new Command(instrument.getDShortName(), direction, slp, tpp, instrument.quantity, "B" + labelId, openPrice).execute();
		}
		else if (op.equals("close_order")) {
			IOrder order = openOrders.get(idx);
			if (false && !order.getInstrument().isTradable()) {
				speak("non tradable");
				return;
			}
			MyStrategy.getContext().executeTask(new CloseOrderTask(order));
		}
		else if (op.equals("update_sl_tp")) {
			if (pendingUpdateOrder == null)
				return;
			if (updateTargetLevel == 0) {
				MyStrategy.getContext().executeTask(new UpdateOrderTask(pendingUpdateOrder));
			}
			else {
				String label = pendingUpdateOrder.getLabel();
				pendingConditionalUpdates.removeIf(p -> p.order.getLabel().equals(label));
				pendingConditionalUpdates.add(new PendingConditionalUpdate(pendingUpdateOrder, updateTargetPrice, updateTargetLevel > 0));
				speak("Will update stop loss and take profit when price reaches " + formatPrice(updateTargetPrice));
			}
		}

	}
	private void reportOpenOrders() {
		if (MyStrategy.getContext() != null) {
			try {
				openOrders = MyStrategy.getContext().getEngine().getOrders();
				op = "open_orders";
				idx = openOrders .size() -1;
				speak(String.format("Open positions: %d", openOrders.size()));
			} catch (JFException e) {
				speak("Error");
			}
		}
		else
			speak("Not connected.");
	}

	private void reportClosedOrders() {
		if (MyStrategy.getContext() != null) {
			try {
				closedOrders = MyStrategy.getContext().getReportService().getClosedPositions(System.currentTimeMillis() - 14 *24*3600*1000 , System.currentTimeMillis());
				// unique values
				closedOrders = new ArrayList<>(new HashSet<>(closedOrders ));

				// sort by date
				Collections.sort(closedOrders, Comparator.comparing(IReportPosition::getOpenTime));

				op = "closed_orders";
				idx = closedOrders.size() -1;
				speak(String.format("Closed positions: %d", closedOrders.size()));
			} catch (JFException e) {
				speak("Error");
			}
		}
		else
			speak("Not connected.");
	}
	private void reportMessage() {
		if (MyStrategy.messages.size() == 0) {
			speak("No message");
			return;
		}
		IMessage message = MyStrategy.messages.get(idx);
		String label = (message.getOrder() != null) ? message.getOrder().getLabel() + "," : "";
		speak(String.format(
				"%d, %s: %s %s, %s",
				idx + 1,
				message.getType().name().replace('_', ' '),
				label,
				(message.getContent() != null) ? message.getContent().replace("null", "") : "",
						MyUtils.formatTime(message.getCreationTime())
				));
	}
	private void reportAccount() {
		if (MyStrategy.getContext() != null) {
			String currency = MyStrategy.getContext().getAccount().getAccountCurrency().getSymbol();
			speak(String.format(
					"Account balance: %s%s, used leverage: %s%%, equity: %s%s",
					MyStrategy.getContext().getAccount().getBalance(),
					currency,
					MyStrategy.getContext().getAccount().getUseOfLeverage(),
					MyStrategy.getContext().getAccount().getEquity(),
					currency
					));
		}
		else {
			speak("Not connected");
		}
	}
	private void reportHistory(Period period) {
		if (MyStrategy.getContext() == null) {
			speak("Please wait");
			op = "";
			return;
		}
		speak("History of lows and highs, loading");
		
		List<IBar>  bars;
		try {
			IHistory history = MyStrategy.getContext().getHistory();
			Instrument x = instruments.get(selectedInstrument).getInstrument();
			long prevBarTime = history.getPreviousBarStart(period, history.getLastTick(x).getTime());
			long startTime =  history.getTimeForNBarsBack(period, prevBarTime, 100);
			//long startTime =  prevBarTime - 3*24*3600*1000;
			bars = history.getBars(x, period, OfferSide.ASK, startTime, prevBarTime);
		} catch (JFException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			speak("Error");
			op = "";
			return;
		}
		textList.clear();
		textListFull.clear();
		for (int i = 0; i < bars.size(); i++) {
			textList.add(String.format(
					"%s: %s: till %s",
					formatPrice(bars.get(i).getLow(), true, instruments.get(selectedInstrument)),
					formatPrice(bars.get(i).getHigh(), true, instruments.get(selectedInstrument)),
					MyUtils.formatTime(bars.get(i).getTime())
					));
			textListFull.add(String.format(
					"%s: %s: till %s",
					formatPrice(bars.get(i).getLow(), false, instruments.get(selectedInstrument)),
					formatPrice(bars.get(i).getHigh(), false, instruments.get(selectedInstrument)),
					MyUtils.formatTime(bars.get(i).getTime())
					));
		}
		op = "text_list";
		idx = textList.size() - 1;
		speak("History ready");

	}

	private static class DayStats {
		String dayName;
		boolean hasData;
		double minPrice;
		long minTime;
		double maxPrice;
		long maxTime;
	}

	private DayStats computeDayStats(int offset) {
		DayStats stats = new DayStats();
		ZoneId zone = ZoneId.systemDefault();
		ZonedDateTime dayStart = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.DAYS).minusDays(offset);
		stats.dayName = dayStart.format(DateTimeFormatter.ofPattern("MMMM d"));

		Instrument instrument = instruments.get(selectedInstrument).getInstrument();
		IHistory history = MyStrategy.getContext().getHistory();

		long startTime = dayStart.toInstant().toEpochMilli();
		long rawEndTime = Math.min(dayStart.plusDays(1).toInstant().toEpochMilli(), System.currentTimeMillis());

		try {
			// the current day's end time is "now", which falls mid-bar; align it to
			// the start of the last completed 5 min bar, or getBars() rejects the interval
			long endTime = history.getPreviousBarStart(Period.FIVE_MINS, rawEndTime);
			if (endTime < startTime) {
				stats.hasData = false;
				return stats;
			}
			List<IBar> bars = history.getBars(instrument, Period.FIVE_MINS, OfferSide.ASK, startTime, endTime);
			if (bars.isEmpty()) {
				stats.hasData = false;
				return stats;
			}
			IBar minBar = bars.get(0);
			IBar maxBar = bars.get(0);
			for (IBar bar : bars) {
				if (bar.getLow() < minBar.getLow())
					minBar = bar;
				if (bar.getHigh() > maxBar.getHigh())
					maxBar = bar;
			}
			stats.hasData = true;
			stats.minPrice = minBar.getLow();
			stats.minTime = minBar.getTime();
			stats.maxPrice = maxBar.getHigh();
			stats.maxTime = maxBar.getTime();
		} catch (JFException e) {
			e.printStackTrace();
			stats.hasData = false;
		}
		return stats;
	}

	private void speakDayStats(DayStats stats) {
		MyInstrument mi = instruments.get(selectedInstrument);
		if (!stats.hasData) {
			speak(stats.dayName + ". No data.");
			return;
		}
		speak(String.format(
				"%s. Min: %s at %s. Max: %s at %s.",
				stats.dayName,
				formatPrice(stats.minPrice, false, mi), MyUtils.formatTime(stats.minTime),
				formatPrice(stats.maxPrice, false, mi), MyUtils.formatTime(stats.maxTime)
				));
	}

	private void reportDay() {
		currentDayStats = computeDayStats(dayOffset);
		speakDayStats(currentDayStats);
	}

	private void speakDayMin() {
		if (currentDayStats == null || !currentDayStats.hasData) {
			speak("No data");
			return;
		}
		MyInstrument mi = instruments.get(selectedInstrument);
		speak(String.format("Min: %s at %s", formatPrice(currentDayStats.minPrice, false, mi), MyUtils.formatTime(currentDayStats.minTime)));
	}

	private void speakDayMax() {
		if (currentDayStats == null || !currentDayStats.hasData) {
			speak("No data");
			return;
		}
		MyInstrument mi = instruments.get(selectedInstrument);
		speak(String.format("Max: %s at %s", formatPrice(currentDayStats.maxPrice, false, mi), MyUtils.formatTime(currentDayStats.maxTime)));
	}

	// the long form used by F8 only: for prices above 1000 the fractional part carries no
	// useful information for a peak, so it is dropped; smaller prices (FX pairs) keep every digit.
	// F5/F6 use plain formatPrice(x, false, ...) instead - this rounding is specific to peaks.
	private String formatPeakPriceLong(double price) {
		if (price > 1000)
			return formatPrice(Math.round(price)).replaceAll("..$", "");
		return formatPrice(price);
	}
	private void reportPeaks() {
		if (MyStrategy.getContext() == null) {
			speak("Please wait");
			op = "";
			return;
		}
		speak("Peaks, loading");
		ITick lastTick;
		List<ITick>  ticks;
		try {
			lastTick = MyStrategy.getContext().getHistory().getLastTick(instruments.get(selectedInstrument).getInstrument());
			ticks = MyStrategy.getContext().getHistory().getTicks(
					instruments.get(selectedInstrument).getInstrument(),
					lastTick.getTime() -12*3600*1000, lastTick.getTime());
		} catch (JFException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			speak("Error");
			op = "";
			return;
		}
		// convert to price time serrie
		double[] signal = new double[ticks.size()];
		for (int i=0; i<ticks.size(); i++)
			signal[i] = (ticks.get(i).getBid() + ticks.get(i).getAsk()) / 2;

		// find peaks
		FindPeak fp = new FindPeak(signal);

		Peak out = fp.detectPeaks();
		int[] peaks = out.filterByProminence(lastTick.getBid() * 0.0010, 1000000.0);

		Peak out2 = fp.detectTroughs();
		int[] troughs = out2.filterByProminence(lastTick.getBid() * 0.0010, 1000000.0);

		double pip = instruments.get(selectedInstrument).instrument.getPipValue();
		
		// combine them. Both maps are keyed by the same tick times, so the two lists stay
		// index-aligned: plain cursor speaks the short price, shift speaks the full one.
		MyInstrument mi = instruments.get(selectedInstrument);
		Map<Long, String> all = new TreeMap<>(); 
		Map<Long, String> allFull = new TreeMap<>(); 
		for (int i: peaks) {
			signal[i]  = Math.round(signal[i]/pip)*pip;
			// remove trailing zeros which sometime apears
			signal[i]   = Math.round(signal[i] * 1000000) / 1000000.0;

			long time = ticks.get(i).getTime();
			all.put(time, String.format(
					"Max: %s. at %s",
					formatPrice(signal[i], true, mi),
					MyUtils.formatTime(time)
					));
			allFull.put(time, String.format(
					"Max: %s. at %s",
					formatPeakPriceLong(signal[i]),
					MyUtils.formatTime(time)
					));
		}

		for (int i: troughs) {
			signal[i]  = Math.round(signal[i]/pip)*pip;
			// remove trailing zeros which sometime apears
			signal[i]   = Math.round(signal[i] * 1000000) / 1000000.0;

			long time = ticks.get(i).getTime();
			all.put(time, String.format(
					"Min: %s. at %s",
					formatPrice(signal[i], true, mi),
					MyUtils.formatTime(time)
					));
			allFull.put(time, String.format(
					"Min: %s. at %s",
					formatPeakPriceLong(signal[i]),
					MyUtils.formatTime(time)
					));
		}

		textList = new ArrayList<String>(all.values());
		textListFull = new ArrayList<String>(allFull.values());
		op = "text_list";
		idx = textList.size() - 1;
		speak("Peaks ready");
	}
	private JSONArray getJSONTicks(Instrument instrument, Long days) {
		List<ITick> ticks = loadTicks(instrument, days);

		JSONArray json = new JSONArray();
		try {
			for (int i=0; i<ticks.size(); i++) {
				ITick tick = ticks.get(i);
				JSONObject row = new JSONObject();
				row.put("time", tick.getTime());
				row.put("ask", tick.getAsk());
				row.put("bid", tick.getBid());
				json.put(row);
			}
		} catch (JSONException e) {
			e.printStackTrace();
			return null;
		}
		return json;
	}

	private JSONArray getJSONBars(Instrument instrument, Long days, Period period) {
		List<IBar> bars = loadBars(instrument, days, period);

		JSONArray json = new JSONArray();
		try {
			for (int i=0; i<bars.size(); i++) {
				IBar bar = bars.get(i);
				JSONObject row = new JSONObject();
				row.put("time", bar.getTime());
				row.put("open", bar.getOpen());
				row.put("high", bar.getHigh());
				row.put("low", bar.getLow());
				row.put("close", bar.getClose());
				row.put("volume", bar.getVolume());

				json.put(row);
			}
		} catch (JSONException e) {
			e.printStackTrace();
			return null;
		}


		return json;
	}
	public void onAPISuccess(String result) {
		JSONObject json ;
		try {
			json = new JSONObject(result);

			speak(json.getString("message"));
		} catch (JSONException e) {
			e.printStackTrace();
			speak("invalid JSON");
			return;
		}
	}
	public void onAPIError(int responseCode) {
		speak("Error " + responseCode);
	}
	private List<ITick> loadTicks(Instrument instrument, Long days) {
		TreeMap<Long, ITick> allTicks = new TreeMap<Long, ITick>();
		try {
			for (Long day = days; day >0; day--) {
				System.out.println(day);
				ITick lastTick = MyStrategy.getContext().getHistory().getLastTick(instrument);
				List<ITick> ticks = MyStrategy.getContext().getHistory().getTicks(
						instrument,
						lastTick.getTime() -day * 24*3600*1000-120000, lastTick.getTime() - (day-1) * 24*3600*1000);
				for (int i=0; i<ticks.size(); i++)
					allTicks.put(ticks.get(i).getTime(), ticks.get(i));
			}
			System.out.println(allTicks.size());
			return new ArrayList<ITick>(allTicks.values());
		}
		catch(Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	private List<IBar> loadBars(Instrument instrument, Long days, Period period) {
		TreeMap<Long, IBar> allBars = new TreeMap<Long, IBar>();
		try {
			for (Long day = days; day >0; day--) {
				System.out.println(day);
				long time = System.currentTimeMillis();
				List<IBar> bars = MyStrategy.getContext().getHistory().getBars(
						instrument,
						period,OfferSide.ASK,
						time -day * 24*3600*1000-120000, time - (day-1) * 24*3600*1000);
				for (int i=0; i<bars.size(); i++)
					allBars.put(bars.get(i).getTime(), bars.get(i));
			}
			System.out.println(allBars.size());
			return new ArrayList<IBar>(allBars.values());
		}
		catch(Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private void buildModel() {
		speak("Building model in background");
		JSONObject json = new JSONObject();
		try {
			json.put("command", "build_model");
			json.put("instrument", instruments.get(selectedInstrument).dShortName);
			json.put("ticks", getJSONTicks(instruments.get(selectedInstrument).getInstrument(), 1l));
			json.put("bars", getJSONBars(instruments.get(selectedInstrument).getInstrument(), 1l, Period.ONE_HOUR));
		} catch (JSONException e1) {
			e1.printStackTrace();
			speak("Error");
			return;
		}
		new Thread(new JSONSender(this, json)).run();


	}
	private void adjustOpenPrice(int direction) {

		double pip = instruments.get(selectedInstrument).instrument.getPipValue();
		openPrice = openPrice * (1 + direction * 0.0002);
		openPrice  = Math.round(openPrice  /pip)*pip;
		// remove trailing zeros which sometime apears
		openPrice   = Math.round(openPrice * 1000000) / 1000000.0;
		System.out.println(openPrice);

		if (openType == TYPE_HEDGE) {
			speak("Hedging at " + openPrice);
		}
		else {
			speak("Target price " + openPrice + ". Press up for buy, down for sell.");
		}
	}

	// rounds to at most 'digits' significant figures, e.g. (52365.21, 5) -> 52365, (6543.21, 5) -> 6543.2
	private double roundToSignificantDigits(double value, int digits) {
		if (value == 0)
			return 0;
		double d = Math.ceil(Math.log10(Math.abs(value)));
		int power = digits - (int) d;
		double magnitude = Math.pow(10, power);
		return Math.round(value * magnitude) / magnitude;
	}

	private void adjustUpdateTarget(int direction) {
		updateTargetLevel += direction;
		if (updateTargetLevel == 0) {
			speak("Now");
			return;
		}
		double mid = (updateInitTick.getAsk() + updateInitTick.getBid()) / 2.0;
		double step = mid / 5000.0;
		double target;
		if (updateTargetLevel > 0)
			target = updateInitTick.getAsk() + updateTargetLevel * step;
		else
			target = updateInitTick.getBid() + updateTargetLevel * step;
		updateTargetPrice = roundToSignificantDigits(target, 5);
		speak("Target: " + formatPrice(updateTargetPrice));
	}

	// called from MyStrategy.onTick for every tick; delegates to the singleton instance
	public static void checkConditionalUpdate(Instrument instrument, ITick tick) {
		if (instance != null)
			instance.checkPendingConditionalUpdate(instrument, tick);
	}

	private void checkPendingConditionalUpdate(Instrument instrument, ITick tick) {
		Iterator<PendingConditionalUpdate> it = pendingConditionalUpdates.iterator();
		while (it.hasNext()) {
			PendingConditionalUpdate p = it.next();
			if (!p.order.getInstrument().equals(instrument))
				continue;
			boolean reached = p.above ? tick.getAsk() >= p.targetPrice : tick.getBid() <= p.targetPrice;
			if (reached) {
				it.remove();
				MyStrategy.getContext().executeTask(new UpdateOrderTask(p.order));
				speak("Target price reached. Updating stop loss and take profit.");
			}
		}
	}

}