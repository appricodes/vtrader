package trader;

import java.awt.Toolkit;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.SwingUtilities;

import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

public class MyStrategy implements IStrategy {
	
	private IHistory history = null;
	private static IContext context;
	public static List<IMessage> messages = new ArrayList<IMessage>();
	
	public void onStart(IContext context) throws JFException {
		this.history = context.getHistory();
		MyStrategy.context = context;

		Main.speak("Ready");

		// load settings of instruments 
		MyInstrument.load();
		//MyInstrument.test();

		// subscribe instruments
		if (MyInstrument.getInstruments().isEmpty()) {
			System.out.println("No instrument is requested.");
			context.stop();
		}
		else {
			Set<Instrument> instruments = new HashSet<Instrument>();
			for (MyInstrument myInstrument: MyInstrument.getInstruments().values()) {
				Instrument instrument = Instrument.fromString(myInstrument.getDName());
				if (instrument != null) {
					instruments.add(instrument);
					myInstrument.setInstrument(instrument);
					myInstrument.setDShortName(instrument.name());
				}
			}
			context.setSubscribedInstruments(instruments, true);
		}

		// make directories
		File dir = new File(Main.fastDir + Main.separator + "Reports");
		if (!dir.exists())
			dir.mkdirs();

		Toolkit.getDefaultToolkit().beep();
	}

	public void onStop() throws JFException {
	}

	public void onTick(Instrument instrument, ITick tick) throws JFException {
	}
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {
	}

	public void onMessage(IMessage message) throws JFException {
		if (message.getType() != IMessage.Type.INSTRUMENT_STATUS) {
			messages.add(message);

			switch (message.getType() ) {
			case ORDER_FILL_OK:
				Main.speak("Order filled successfully.");
				break;
			case ORDER_SUBMIT_REJECTED:
			case ORDER_FILL_REJECTED:
				Main.speak("Order rejected.");
				break;
			case ORDER_CLOSE_OK:
				double profit = Math.round((message.getOrder().getProfitLossInAccountCurrency() + message.getOrder().getCommission()) * 100) / 100.0;
				Main.speak("Order closed. Profit: " + profit);
				break;
			case ORDER_CLOSE_REJECTED:
				Main.speak("Rejected closing.");
				break;
			case ORDER_CHANGED_OK:
				Main.speak("Order changed successfully.");
				break;
			case ORDER_CHANGED_REJECTED:
				Main.speak("Rejected changing order.");
				break;
			}
			MyUtils.beep(1);
		}
		// if this is an order-related message 
		if (message .getOrder() != null) {
			MyUtils.addLog("D-" + message.getOrder().getLabel(),message.getType().name(), message.getContent(), context.getAccount().getBalance());
		}
	}

	public void onAccount(IAccount account) throws JFException {
	}
	
	public static IContext getContext() {
		return context;
	}
}