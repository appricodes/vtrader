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
	public static final int OPERATION_HEDGE = 3;
	private IContext context;
	private MyInstrument myInstrument;
	private int operation;
	private double stopLoss;
	private double takeProfit;
	private double amount;
	private double desiredPrice = 0;
	private String label;
	private IOrder order;

	class SubmitOrderTask implements Callable<Boolean> {
		int op;

		public SubmitOrderTask(int op) {
			this.op = op;
		}

		@Override
		public Boolean call(){
			submitOrder(op);
			return true;
		}
	}

	public Command(String instrumentName, int operation, double stopLoss, double takeProfit, double amount, String label) {
		this(instrumentName, operation, stopLoss, takeProfit, amount, label, 0);
	}
	public Command(String instrumentName, int operation, double stopLoss, double takeProfit, double amount, String label, double desiredPrice) {
		this.context = MyStrategy.getContext();
		this.operation = operation;
		this.myInstrument = MyInstrument.getInstrumentByShortName(instrumentName);
		
		this.stopLoss = stopLoss;
		this.takeProfit= takeProfit;
		this.amount = amount;
		this.desiredPrice = desiredPrice;
		this.label = label;
		

		double pip = this.myInstrument.getInstrument().getPipValue();
		this.stopLoss = Math.round(this.stopLoss/pip)*pip;
		this.takeProfit = Math.round(this.takeProfit/pip)*pip;
		this.desiredPrice = Math.round(this.desiredPrice /pip)*pip;
	}
	public void execute() {
		if (Main.isStopping()) 
			return;
		// add command to logs
		String operationName = (operation == OPERATION_BUY) ? "buy" : ((operation == OPERATION_SELL) ? "Sell" : "Hedge");
		MyUtils.addLog("commands", operationName, myInstrument.getInstrument().name() + "," + operation + "," + stopLoss + "," + takeProfit + "," + context.getAccount().getBalance(), context.getAccount().getBalance());
		SubmitOrderTask task = new SubmitOrderTask(operation);
		context.executeTask(task);
	}

	private void submitOrder(int op) {
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
		System.out.println("Desired price: " + this.desiredPrice);
		System.out.println("Stop loss: " + this.stopLoss);
		System.out.println("Take profit: " + this.takeProfit);
		
		String comment = "";
		// Broker: D
		if (Main.prop.getProperty("trade.d", "1").equals("1")) {
			Main.speak("Starting");
			double orderAmount = Math.round(amount / instrument.getTradeAmountIncrement()) * instrument.getTradeAmountIncrement();
			orderAmount = orderAmount / 1000000;


			try {
				System.out.println("Submitting D order...");
				Main.speak("Submitting order");
				long gtt = lastTick.getTime() + 24 * 3600 * 1000; //withdraw after this time
				// buy at market price
				if (op == OPERATION_BUY && desiredPrice == 0) {
					double slp = lastTick.getBid() - stopLoss;
					double tpp = lastTick.getBid() +  takeProfit;
					order = context.getEngine().submitOrder(label, instrument, OrderCommand.BUY, orderAmount, 0, slippage, slp, tpp, 0, comment);
				}
				// sell at market price
				else if (op == OPERATION_SELL && desiredPrice == 0) {
					double slp = lastTick.getAsk() + stopLoss;
					double tpp = lastTick.getAsk() - takeProfit;
					order = context.getEngine().submitOrder(label, instrument, OrderCommand.SELL, orderAmount, 0, slippage, slp, tpp, 0, comment);
				}
				// buy at desired price
				else if (op == OPERATION_BUY && desiredPrice > 0) {
					double slp = desiredPrice - stopLoss;
					double tpp = desiredPrice +  takeProfit;
					// price below market: buy on dip (limit); above market: buy on breakout (stop)
					OrderCommand cmd = (desiredPrice < lastTick.getAsk()) ? OrderCommand.BUYLIMIT : OrderCommand.BUYSTOP;
					order = context.getEngine().submitOrder(label, instrument, cmd, orderAmount, desiredPrice, slippage, slp, tpp, gtt, comment);
				}
				// sell at desired price
				else if (op == OPERATION_SELL && desiredPrice > 0) {
					double slp = desiredPrice + stopLoss;
					double tpp = desiredPrice - takeProfit;
					// price above market: sell on rally (limit); below market: sell on breakout (stop)
					OrderCommand cmd = (desiredPrice > lastTick.getBid()) ? OrderCommand.SELLLIMIT : OrderCommand.SELLSTOP;
					order = context.getEngine().submitOrder(label, instrument, cmd, orderAmount, desiredPrice, slippage, slp, tpp, gtt, comment);
				}
				// Hedging  at market price
				else if (op == OPERATION_HEDGE && desiredPrice == 0) {
					// buy position
					double slp = lastTick.getBid() - stopLoss;
					double tpp = lastTick.getBid() +  takeProfit;
					order = context.getEngine().submitOrder(label + "_buy", instrument, OrderCommand.BUY, orderAmount, 0, slippage, slp, tpp, 0, comment);
					
					// sell position
					slp = lastTick.getAsk() + stopLoss;
					tpp = lastTick.getAsk() - takeProfit;
					order = context.getEngine().submitOrder(label + "_sell", instrument, OrderCommand.SELL, orderAmount, 0, slippage, slp, tpp, 0, comment);
				}
				// Hedging  at desired price
				else if (op == OPERATION_HEDGE && desiredPrice > 0) {
					// buy position
					double slp = desiredPrice - stopLoss;
					double tpp = desiredPrice +  takeProfit;
					if (desiredPrice < lastTick.getBid())
						order = context.getEngine().submitOrder(label + "_buy", instrument, OrderCommand.BUYLIMIT, orderAmount, desiredPrice, slippage, slp, tpp, gtt, comment);
					else
						order = context.getEngine().submitOrder(label + "_buy", instrument, OrderCommand.BUYSTOP, orderAmount, desiredPrice, slippage, slp, tpp, gtt, comment);
					
					// sell position
					slp = desiredPrice + stopLoss;
					tpp = desiredPrice - takeProfit;
					if (desiredPrice < lastTick.getBid())
						order = context.getEngine().submitOrder(label + "_sell", instrument, OrderCommand.SELLSTOP_BYASK, orderAmount, desiredPrice, slippage, slp, tpp, gtt, comment);
					else
						order = context.getEngine().submitOrder(label + "_sell", instrument, OrderCommand.SELLLIMIT_BYASK, orderAmount, desiredPrice, slippage, slp, tpp, gtt, comment);
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
