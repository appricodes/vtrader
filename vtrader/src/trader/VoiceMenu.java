package trader;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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

	private List<MyInstrument> instruments ;
	private int selectedInstrument = 0;
	private String op = "instrument";
	private int openType = TYPE_NONE;
	private int idx = 0;
	private int rate = 25; // speech rate
	private List<IOrder> openOrders;
	private List<IReportPosition>  closedOrders = new ArrayList<IReportPosition>();
	private List<String> textList = new ArrayList<String>();

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

			double slp;
			double tpp;

			if (order.isLong()) {
				slp = lastTick.getAsk() - instruments.get(selectedInstrument).slp;
				tpp = lastTick.getBid() +  instruments.get(selectedInstrument).tpp;
			} 
			else {
				slp = lastTick.getBid() + instruments.get(selectedInstrument).slp;
				tpp = lastTick.getAsk() - instruments.get(selectedInstrument).tpp;
			}
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
		SineWaveGenerator generator = new SineWaveGenerator();
		//generator.play();
		MyInstrument.load();
		instruments = new ArrayList<>(MyInstrument.getInstruments().values());
		for (int i=0; i<instruments.size(); i++) {
			System.out.println(instruments.get(i).name);
		}
	}
	private String formatPrice(double price) {
		return formatPrice(price, false);
	}
	private String formatPrice(double price, boolean shorter) {
		String s = String.valueOf(price);
		if (shorter)
			s = s.replaceFirst(".*(\\d)(\\d\\d)\\.(\\d)(\\d).*", "$1 $2 dot $3");
		else
			s = s.replaceFirst("(\\d)(\\d\\d\\d)\\.", "$1, $2.");
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
	private double getPrice(Instrument instrument, boolean bid) {
		if (MyStrategy.getContext() == null) {
			speak("Please wait.");
			return 0;
		}

		IHistory history = MyStrategy.getContext().getHistory();
		double price;
		try {
			ITick tick = history.getLastTick(instrument);
			if (bid)
				price = tick.getBid();
			else
				price = tick.getAsk();
		} catch (JFException e) {
			speak("Error getting price");
			price = 0;
		}
		return price;
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
			public void keyPressed(KeyEvent e) {
				//public void keyReleased(KeyEvent e) {
				switch(e.getKeyCode()) {
				case  KeyEvent.VK_UP:
					processCursor(1);
					break; 
				case  KeyEvent.VK_DOWN:
					processCursor(-1);
					break;
				case  KeyEvent.VK_PAGE_UP:
					processCursor(10);
					break;
				case  KeyEvent.VK_PAGE_DOWN:
					processCursor(-10);
					break;
				case  KeyEvent.VK_HOME:
					processCursor(1000000000);
					break;
				case  KeyEvent.VK_END:
					processCursor(-1000000000);
					break;
				case  KeyEvent.VK_1:
					// instrument selection
					op = "instrument";
					reportInstrument();
					break;
				case  KeyEvent.VK_2:
					reportPrice(TYPE_SELL, false);
					break;
				case  KeyEvent.VK_LEFT:
					reportPrice(TYPE_SELL, true);
					break;
				case  KeyEvent.VK_RIGHT:
					reportPrice(TYPE_BUY, true);
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
						speak(String.format(
								"Update stop loss and take profit of %s order %s, to: %s, and %s? Press space to confirm.",
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
					speak("Open new position");
					break;
				case  KeyEvent.VK_SHIFT:
					op = "messages";
					idx = MyStrategy.messages.size() - 1;
					reportMessage();
					break;
				case  KeyEvent.VK_CAPS_LOCK:
					processRate();
					break;
				case  KeyEvent.VK_SPACE:
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
					reportHistory();
					break;
				case  KeyEvent.VK_F6:
					reportPeaks();
					break;
				case  KeyEvent.VK_F12:
					speak(MyUtils.formatTime(System.currentTimeMillis()));
					break;
				}
			}
		});

		frame.setVisible(true);

	}
	private void processCursor(int direction) {
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
			instruments.get(selectedInstrument).slp = increasePrice(instruments.get(selectedInstrument).slp, direction);
			speak(formatPrice(instruments.get(selectedInstrument).slp));
		}
		else if (op.equals("tpp")) {
			instruments.get(selectedInstrument).tpp = increasePrice(instruments.get(selectedInstrument).tpp, direction);
			speak(formatPrice(instruments.get(selectedInstrument).tpp));
		}
		else if (op.equals("quantity")) {
			if (instruments.get(selectedInstrument).quantity  < 10)
				direction *= 10;
			instruments.get(selectedInstrument).quantity = (int)increasePrice(instruments.get(selectedInstrument).quantity, direction);
			if (instruments.get(selectedInstrument).quantity < 1)
				instruments.get(selectedInstrument).quantity = 1;
			speak(String.format("%d", instruments.get(selectedInstrument).quantity));
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
					"%s, profit: %s, %s, %d x, %s, open price: %s, time: %s",
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
			double profit = Math.round((position.getProfitLoss().getAmount() + position.getCommission().getAmount()) *100) / 100.0;
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
	private void reportPrice(int type, boolean shorter) {
		if (MyStrategy.getContext() != null) {
			double price = getPrice(instruments.get(selectedInstrument).getInstrument(), type == TYPE_SELL);
			speak(formatPrice(price, shorter));
		}
		else {
			speak("Please wait.");
		}

	}
	private void reportSLP() {
		double slp = instruments.get(selectedInstrument).slp;
		speak(String.format("Stop loss %s", formatPrice(slp)));
	}
	private void reportTPP() {
		double tpp = instruments.get(selectedInstrument).tpp;
		speak(String.format("Take profit %s", formatPrice(tpp)));
	}
	private void reportQuantity() {
		speak(String.format("Quantity %d", instruments.get(selectedInstrument).quantity));
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
		speak(String.format("%s, %d x %s, risk: %s, stop loss: %s, take profit: %s, Press space to confirm.",
				direction, 
				instrument.quantity,  instrument.name,
				formatPrice(Math.round(instrument.quantity * instrument.slp)),
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
			int direction = (openType == TYPE_BUY) ? Command.OPERATION_BUY : Command.OPERATION_SELL;
			int labelId  = getLabelId();
			new Command(instrument.getDShortName(), direction, instrument.slp, instrument.tpp, instrument.quantity, "B" + labelId).execute();;
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
			IOrder order = openOrders.get(idx);
			MyStrategy.getContext().executeTask(new UpdateOrderTask(order));
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
	private void reportHistory() {
		if (MyStrategy.getContext() == null) {
			speak("Please wait");
			op = "";
			return;
		}
		speak("History, loading");
		IBar lastBar;
		List<IBar>  bars;
		try {
			lastBar = MyStrategy.getContext().getHistory().getBar(instruments.get(selectedInstrument).getInstrument(), Period.FIVE_MINS ,OfferSide.ASK, 0);
			bars = MyStrategy.getContext().getHistory().getBars(
					instruments.get(selectedInstrument).getInstrument(),
					Period.FIVE_MINS ,OfferSide.ASK, 
					lastBar.getTime() -24*3600*1000, lastBar.getTime());
		} catch (JFException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			speak("Error");
			op = "";
			return;
		}
		textList.clear();
		for (int i = 0; i < bars.size(); i++) {
			String high = formatPrice(Math.round(bars.get(i).getHigh()), false);
			high = high.substring(high.length()-3);
			textList.add(String.format(
					"%s: %s: at %s",
					formatPrice(Math.round(bars.get(i).getLow()), false),
					high,
					MyUtils.formatTime(bars.get(i).getTime())
					));
		}
		op = "text_list";
		idx = textList.size() - 1;
		speak("History ready");

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

		// combine them
		Map<Long, String> all = new TreeMap<>(); 
		for (int i: peaks) {
			String value;
			if (signal[i] > 1000)
				value = formatPrice(Math.round(signal[i]));
			else
				value = formatPrice(signal[i]);
			String s = String.format(
					"Max: %s. at %s. %s",
					value.substring(value.length()-3),
					MyUtils.formatTime(ticks.get(i).getTime()),
					value
					);
			all.put(ticks.get(i).getTime(), s);
		}

		for (int i: troughs) {
			String value;
			if (signal[i] > 1000)
				value = formatPrice(Math.round(signal[i]));
			else
				value = formatPrice(signal[i]);

			String s = String.format(
					"Min: %s. at %s. %s",
					value.substring(value.length()-3),
					MyUtils.formatTime(ticks.get(i).getTime()),
					value
					);
			all.put(ticks.get(i).getTime(), s);
		}

		textList = new ArrayList<String>(all.values());
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
			speak("error");
			return;
		}
		new Thread(new JSONSender(this, json)).run();


	}

}