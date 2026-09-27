package trader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

import com.dukascopy.api.IHistory;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.Arrays;
import java.util.Comparator; // Import Comparator
class MyInstrument {
	
	public String name;
	public String dName;
	public String dShortName;
	public String fxcmName;
	public  double slp;
	public double tpp;
	public int quantity;
	// % of balance used as margin (key 6, risk mode). Stored directly instead of re-derived from
	// quantity each time, so repeated up/down presses don't get stuck on high-priced instruments
	// where rounding quantity to an int loses the percent. -1 = not yet initialized.
	public int percent = -1;
	public int digits;
	public int skipDigits;
	public int fxcmLotSize;
	public String timezone;
	public Instrument instrument = null;
	public Properties prop;

	private static LinkedHashMap<String, MyInstrument> instruments = new LinkedHashMap<>();
	private static HashMap<String, String> fToD = new HashMap<String, String>();
	private static HashMap<String, String> shortToD = new HashMap<String, String>();

	public MyInstrument(File file) {
		prop = new Properties();
		InputStream is = null;
		try {
			is = new FileInputStream(file);
		} catch (FileNotFoundException ex) {
			Main.LOGGER.error("Cannot found instrument file: " + file.getAbsolutePath());
			return;
		}
		try {
			prop.load(is);
		} catch (IOException ex) {
			Main.LOGGER.error("Cannot read instrument file: " + file.getAbsolutePath());
			return;
		}
		this.name = prop.getProperty("name", "unknown").trim();
		this.dName = prop.getProperty("d.name", "").trim();
		this.slp = Double.parseDouble(prop.getProperty("slp", "5").trim());
		this.tpp = Double.parseDouble(prop.getProperty("tpp", "20").trim());
		this.quantity = Integer.parseInt(prop.getProperty("quantity", "20").trim());
		this.digits = Integer.parseInt(prop.getProperty("digits", "20").trim());
		this.skipDigits = Integer.parseInt(prop.getProperty("skip_digits", "20").trim());
		this.instrument = Instrument.fromString(this.dName);
		this.dShortName = this.instrument.name();
		
		this.fxcmName = prop.getProperty("fxcm.name", this.dName).trim();
		this.fxcmLotSize = Integer.parseInt(prop.getProperty("fxcm.lot.size", "1000").trim());

		// converters from shortName and fxcmName to dName
		MyInstrument.fToD.put(this.fxcmName, this.dName);
		MyInstrument.shortToD.put(this.dShortName, this.dName);


		this.timezone = prop.getProperty("market.timezone", "NOT_SET");
		
	}

	public MyInstrument(String csvLine) {
		String[] a = csvLine.split(",");
		this.dName = a[0].trim();
		this.fxcmName = a[4].trim();
		this.fxcmLotSize = Integer.parseInt(a[5].trim());

		// converters from shortName and fxcmName to dName
		MyInstrument.fToD.put(this.fxcmName, this.dName);
	}

	public String getDName() {
		return this.dName;
	}

	public void setDShortName(String dShortName) {
		this.dShortName = dShortName;
		MyInstrument.shortToD.put(this.dShortName, this.dName);
	}

	public String getDShortName() {
		return this.dShortName;
	}

	public String getFXCMName() {
		return this.fxcmName;
	}

	
	public int getFXCMLotSize() {
		return this.fxcmLotSize;
	}
	
	public void setInstrument(Instrument instrument) {
		this.instrument = instrument;
	}

	public Instrument getInstrument() {
		return instrument;
	}

	public boolean isTradingActive() {
		return true;
	}
	public static void load() {
		// is it already loaded?
		if (instruments.size() > 0)
			return;
		File dir = new File(Main.baseDir + "/my_config/instruments");
		
		File[] files = dir.listFiles(); // Get the list of files
		
		// IMPORTANT: Check if listFiles() returned null
		// This happens if 'dir' is not a directory, doesn't exist, or an I/O error occurs.
		if (files == null) 
			return;
		
		Arrays.sort(files, new Comparator<File>() {
			@Override
			public int compare(File f1, File f2) {
				// Compare files based on their names lexicographically (alphabetically)
				return f1.getName().compareTo(f2.getName());
			}
		});
		for (File file : files) {
			System.out.println(file.getName());
			MyInstrument myInstrument = new MyInstrument(file);
			if (!myInstrument.getDName().equals(""))
				instruments.put(myInstrument.dName, myInstrument);
		}

	}

	public static MyInstrument getInstrumentByDName(String dName) {
		if (instruments.containsKey(dName)) {
			return instruments.get(dName);
		} else
			return null;
	}

	public static MyInstrument getInstrumentByShortName(String shortName) {
		if (!shortToD.containsKey(shortName))
			return null;
		String dName = shortToD.get(shortName);
		return getInstrumentByDName(dName);
	}
	public static MyInstrument getInstrumentByFXCMName(String fxcmName) {
		if (!fToD.containsKey(fxcmName))
			return null;
		String dName = fToD.get(fxcmName);
		return getInstrumentByDName(dName);
	}

	public static HashMap<String, MyInstrument> getInstruments() {
		return instruments;
	}
	
}