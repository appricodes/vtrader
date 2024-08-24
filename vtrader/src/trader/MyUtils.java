package trader;


import java.awt.Toolkit;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;

//import javax.xml.bind.DatatypeConverter;

import com.dukascopy.api.ICurrency;
import com.dukascopy.api.JFCurrency;
import java.math.BigInteger;

public class MyUtils {
	public static final int REPORT_GET_PHP = 1;
	private static HashMap<String, Long> watch = new HashMap<>();
	private static HashMap<String, String> instrumentLongNames = new HashMap<String, String>();
	public static void startWatch(String name) {
		watch.put(name, System.currentTimeMillis());
	}
	public static Long readWatch(String name) {
		if (watch.containsKey(name))
			return System.currentTimeMillis() - watch.get(name);
		else
			return -1l;
			
	}
	public static Long stopWatch(String name) {
		if (watch.containsKey(name)) {
			Long t = System.currentTimeMillis() - watch.get(name);
			watch.remove(name);
			return t;
		}
		else
			return -1l;
	}
	public static boolean isWatchActive(String name) {
		return watch.containsKey(name);
	}
	public static void sleep(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			//e.printStackTrace();
		}

	}

	public static void addLog(String name, String code, String message, double balance) {
		File dir = new File(Main.fastDir + Main.separator + "order_logs");
		if (!dir.exists())
			dir.mkdirs();
		name = name.replaceAll("[^A-Za-z0-9\\-]", "");

		String str = formatDateTime() + "\n"
				+ " " + code + "\n"
				+ " " + message + "\n"
				+ "\n";
		// append to file
		File file = new File(dir, name + ".txt");
		try {
			BufferedWriter out = new BufferedWriter(new FileWriter(file, true));
			out.write(str);
			out.close();
		} catch (IOException e) {
		}
		
		// send reports to web
		if (Main.prop.getProperty("web.reporting", "0").equals("1")) {
			final String strName = name;
			Runnable task = new Runnable() {
				public void run() {
					Long time = System.currentTimeMillis();
					String shortMessage = code;
					if (message != null)
						shortMessage += ", " + message.substring(0, Math.min(16, message.length()));
					String strObject = Main.prop.getProperty("web.appId", "") + " " + strName;
					String q = "";
					try {
						q += "id=" + URLEncoder.encode(strObject, StandardCharsets.UTF_8.toString());
						q += "&msg=" + URLEncoder.encode(str, StandardCharsets.UTF_8.toString());
						q += "&short=" + URLEncoder.encode(shortMessage, StandardCharsets.UTF_8.toString());
						q += "&balance=" + URLEncoder.encode(balance + "", StandardCharsets.UTF_8.toString());
					}catch (Exception e) {
					}
					q += "&time=" + time;
					q += "&md5=" + hexHash(time + strObject+ str + shortMessage + balance + Main.salt);
					//System.out.println(Main.prop.getProperty("web.interface") + "add_event.php?" + q);
					String html = getWebpage(Main.prop.getProperty("web.interface") + "add_event.php?" + q);
					//System.out.println("addEventWeb: " + html);
				}
			};
			Thread thread = new Thread(task);
			thread.start();
		}

	}
	
	public static String formatDateTime() {
		return formatDateTime(System.currentTimeMillis());
	}

	public static String formatDateTime(Long timestamp) {
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
		Timestamp ts = new Timestamp(timestamp);
		return formatter.format(ts.toLocalDateTime());
	}
	public static String formatDateTimeShort(Long timestamp) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM, H:mm");
Timestamp ts = new Timestamp(timestamp);
return formatter.format(ts.toLocalDateTime());
}
	public static String formatTime(Long timestamp) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
		Timestamp ts = new Timestamp(timestamp);
		return formatter.format(ts.toLocalDateTime());
	}

	public static String getWebpage(String strURL) {
		try {
			URL url = new URL(strURL);
			BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
			String line;
			StringBuilder sb = new StringBuilder();
			while ((line = br.readLine()) != null) {
				sb.append(line);
				sb.append(System.lineSeparator());
			}
			return sb.toString();
		}
		catch (Exception e) {
			// TODO: handle exception
		}
		return null;
	}
	public static String hexHash(String s) {
		try {
			MessageDigest digest = MessageDigest.getInstance("md5");
			byte[] hash = digest.digest(s.getBytes());
			BigInteger hashInt = new BigInteger(1, hash);
			return hashInt.toString(16);
		} catch (NoSuchAlgorithmException e) {
			System.out.println(e);
			System.exit(0);
			return "";
		}
	}
	
	public static void reportProblem(int type, String message) {
		
	}
	public static String getInstrumentLongName(String shortName) {
		if (instrumentLongNames.size() == 0) {
			try {
				File file = new File(Main.baseDir + Main.separator + "my_config" + Main.separator + "instruments.csv");
				if (file.exists()) {
					FileReader fr = new FileReader(file); //reads the file  
					BufferedReader br=new BufferedReader(fr);  //creates a buffering character input stream  
					String strLine;
					// skip the first line
					strLine=br.readLine();
					while((strLine=br.readLine())!=null) {
						strLine = strLine.trim();
						if (strLine.length() >0) {
							String[] arrayParts = strLine.split(",");
							instrumentLongNames.put(arrayParts[1], arrayParts[0]);
						}
					}
					fr.close();
				}
				else {
					System.out.println("File not found: instruments.csv");
				}
			}
			catch(IOException e) {
				e.printStackTrace();
			}
		}
		return instrumentLongNames.get(shortName);
	}
	public static void filePutContents(File file, String s) {
		try {
			BufferedWriter out = new BufferedWriter(new FileWriter(file));
			out.write(s);
			out.close();
		} catch (IOException e) {
		}

	}
	// returns contents of a text file
	public static String fileGetContents(File file) {
		try {
			if (file.exists()) {
				Path path = file.toPath();
				return String.join("\n", Files.readAllLines(path));
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	public static String urlEncode(String s) {
		try {
			return URLEncoder.encode(s, StandardCharsets.UTF_8.toString());
		} catch (UnsupportedEncodingException ex) {
			throw new RuntimeException(ex.getCause());
		}
	}
	public static void beep(int count) {
		for (int i =0; i<count; i++) {
			Toolkit.getDefaultToolkit().beep();
			sleep(500);
		}
	}
	public static double convertToAccountCurrency(String accountName, String fromCurrency, double fromAmount) {
		try {
			ICurrency to = JFCurrency.getInstance(Main.prop.getProperty(accountName.trim().toLowerCase() + ".account.currency", "EUR").trim().toUpperCase());
			ICurrency from = JFCurrency.getInstance(fromCurrency.trim().toUpperCase());
			double rate = MyStrategy.getContext().getUtils().getRate(from, to);
			return rate * fromAmount;
		} catch (Exception e) {
			Main.LOGGER.error("Cannot get exchange rate from " + fromCurrency + " to account currency.");
			return -1;
		}
	}
}