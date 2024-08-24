package trader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;

public class Command {
	public static final int OPERATION_BUY = 1;
	public static final int OPERATION_SELL = 2;
	private IContext context;
	private MyInstrument myInstrument;
	private int operation;
	private double stopLoss;
	private double takeProfit;
	private double amount;
	private String label;
	private IOrder order;

	class SubmitOrderTask implements Callable<Boolean> {
		boolean isBuy;

		public SubmitOrderTask(boolean isBuy) {
			this.isBuy = isBuy;
		}

		@Override
		public Boolean call(){
			submitOrder(isBuy);
			return true;
		}
	}

	public Command(String instrumentName, int operation, double stopLoss, double takeProfit, double amount, String label) {
		this.context = MyStrategy.getContext();
		this.operation = operation;
		this.myInstrument = MyInstrument.getInstrumentByShortName(instrumentName);
		this.stopLoss = stopLoss;
		this.takeProfit= takeProfit;
		this.amount = amount;
		this.label = label;

		double pip = this.myInstrument.getInstrument().getPipValue();
		this.stopLoss = Math.round(this.stopLoss/pip)*pip;
		this.takeProfit = Math.round(this.takeProfit/pip)*pip;
	}
	public void execute() {
		if (Main.isStopping()) 
			return;
		// add command to logs
		String operationName = (operation == OPERATION_BUY) ? "buy" : "sell";
		MyUtils.addLog("commands", operationName, myInstrument.getInstrument().name() + "," + operation + "," + stopLoss + "," + takeProfit + "," + context.getAccount().getBalance(), context.getAccount().getBalance());
		switch (operation) {
		case OPERATION_BUY:
			SubmitOrderTask buyTask = new SubmitOrderTask(true);
			context.executeTask(buyTask);
			break;
		case OPERATION_SELL:
			SubmitOrderTask sellTask = new SubmitOrderTask(false);
			context.executeTask(sellTask);
			break;

		default:
			break;
		}

	}

	private void submitOrder(boolean isBuy) {
		if (!Main.getClient().isConnected()) {
			Main.LOGGER.warn("submitOrder: D is disconnected");
			Main.speak("Not connected to the server.");
			return;
		}
		Instrument instrument = myInstrument.getInstrument();

		// is trading active for this instrument right now?
		if (!myInstrument.isTradingActive()) {
			Main.LOGGER.info("Trading is off for " + myInstrument.getDShortName());
			Main.speak("Trading is off for " + myInstrument.name);
			return;
		}

		ITick lastTick = null; 
		try {
			lastTick = context.getHistory().getLastTick(instrument);
		}catch (Exception e) {
			e.printStackTrace();
			Main.speak("Error");
			return;
		}

		double slippage = lastTick.getBid() * 0.0002 / instrument.getPipValue();
		System.out.println("Slippage: " + slippage);
		String comment = "";
		// Broker: D
		if (Main.prop.getProperty("trade.d", "0").equals("1")) {
			double orderAmount = Math.round(amount / instrument.getTradeAmountIncrement()) * instrument.getTradeAmountIncrement();
			orderAmount = orderAmount / 1000000;


			try {
				System.out.println("Submitting D order...");
				Main.speak("Submitting order");
				if (isBuy) {
					double slp = lastTick.getAsk() - stopLoss;
					double tpp = lastTick.getBid() +  takeProfit;
					order = context.getEngine().submitOrder(label, instrument, OrderCommand.BUY, orderAmount, 0, slippage, slp, tpp, 0, comment);
				} 
				else {
					double slp = lastTick.getBid() + stopLoss;
					double tpp = lastTick.getAsk() - takeProfit;
					order = context.getEngine().submitOrder(label, instrument, OrderCommand.SELL, orderAmount, 0, slippage, slp, tpp, 0, comment);
				}
				System.out.println("D Order submitted");
				if (order != null) { 
					Main.LOGGER.info("D-" + label, "submitted_successfully");
					//Main.speak("Order sent.");
				}
				MyUtils.addLog("New Dukascopy submission: " + myInstrument.getDShortName() , "StopLoss: " + stopLoss + ", takeProfit: " + takeProfit + ", amount: " + orderAmount, "", 0);
			} catch (Exception e) {
				Main.LOGGER.error("D submit_failed: " + instrument.name(), e);
				MyUtils.addLog("D-error "  + myInstrument.getDShortName(), "submit_failed", e.toString(), 0);
				Main.speak("Submission failed.");
			}
		}
		
	}

	public IOrder getOrder() {
		return order;
	}

}
