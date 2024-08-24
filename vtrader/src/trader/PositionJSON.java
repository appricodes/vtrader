package trader;

import java.lang.reflect.Field;

class PositionJSON {
	public String id;
	public String broker;
	public long openTime;
	public double openPrice;
	public String dName;
	public double amount;
	public double closePrice = 0;
	public long closeTime=0;
	public double commission = 0;
	public double profitLoss = 0;
	// constructor
	public PositionJSON(String broker, String id, String dName, long openTime, double openPrice, double amount) {
		this.broker = broker;
		this.id = id;
		this.openTime = openTime;
		this.openPrice = openPrice;
		this.amount = amount;
		this.dName = dName;
	}
	public String toJSON() {
		StringBuilder sb = new StringBuilder();
		sb.append("{");
		//determine fields declared in this class only (no fields of superclass)
		Field[] fields = this.getClass().getDeclaredFields();
		for ( Field field : fields  ) {
			try {
				sb.append( "\"" + field.getName() + "\":");
				sb.append( "\"" + field.get(this) + "\",");
			} catch ( IllegalAccessException ex ) {
				System.out.println(ex);
			}
		}
		return sb.toString().substring(0, sb.toString().length()-1)+ "}";
		
	}
	
}