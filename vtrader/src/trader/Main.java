
package trader;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dukascopy.api.INewsFilter;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.IPreferences;
import com.dukascopy.api.system.ISystemListener;

import io.github.jonelo.jAdapterForNativeTTS.engines.SpeechEngineNative;
import io.github.jonelo.jAdapterForNativeTTS.engines.Voice;
import io.github.jonelo.jAdapterForNativeTTS.engines.VoicePreferences;
import io.github.jonelo.jAdapterForNativeTTS.engines.exceptions.SpeechEngineCreationException;
import io.github.jonelo.jAdapterForNativeTTS.engines.Voice;
import io.github.jonelo.jAdapterForNativeTTS.engines.SpeechEngine;
import io.github.jonelo.jAdapterForNativeTTS.engines.SpeechEngineNative;
import io.github.jonelo.jAdapterForNativeTTS.engines.VoicePreferences;
import io.github.jonelo.jAdapterForNativeTTS.engines.exceptions.SpeechEngineCreationException;

public class Main {
	protected static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
	
	protected static String separator= "/"; 
	protected static String baseDir = System.getProperty("user.dir");
	// path to a folder on a fast drive for exchanging data
	protected static String fastDir = null;
	protected static boolean opclose;
	// to be used for md5 calculation
	protected static final String salt = "GluE95428!";
	private static boolean isDemoMode;
	public static SpeechEngine speechEngine;
	private static int voiceId = -9999;
	public static Properties prop;
	private static String jnlpUrl;
	private static String userName;
	private static String password;

	private static IClient client;

	private static int lightReconnects = 3;

	public static void main(String[] args) throws Exception {
		VoiceMenu menu = new VoiceMenu();
		menu.start();
		if (false)
			return;
		String s = "test";
		System.out.println("Starting...");
		// get password
		if (false) { 
			Scanner console = new Scanner(System.in);
			System.out.println("Password:");
			String password = console.nextLine();
			if (!password.equals("kmk")) {
				System.out.println("Incorrect password");
				System.exit(0);
			}
		}
		
		// load config.properties
		loadConfig();
		
		// get the instance of the IClient interface
		client = ClientFactory.getDefaultInstance();
		//File cacheDir =new File(Main.fastDir + "\\cache");
		//client.setCacheDirectory(cacheDir);

		setSystemListener();
		tryToConnect();
		
		// set prefferances
		IPreferences pref = client.getPreferences().platform().platformSettings()
				.skipTicks(false)
				.preferences();
		client.setPreferences(pref);

		LOGGER.info("Starting strategy");
		opclose = prop.getProperty("operation.close", "0").equals("1");
		
		//salt = prop.getProperty("md5.salt", "").trim();
		client.startStrategy(new MyStrategy());
		// now it's running
	}

	private static void setSystemListener() {
		// set the listener that will receive system events
		client.setSystemListener(new ISystemListener() {

			@Override
			public void onStart(long processId) {
				LOGGER.info("Strategy started: " + processId);
				
				// subscribe to news
				//client.addNewsFilter(new INewsFilter() {});
			}

			@Override
			public void onStop(long processId) {
				LOGGER.info("Strategy stopped: " + processId);
				if (client.getStartedStrategies().size() == 0) {
					LOGGER.info("Finished");
					System.exit(0);
				}
			}

			@Override
			public void onConnect() {
				LOGGER.info("Connected");
				lightReconnects = 3;
			}

			@Override
			public void onDisconnect() {
				tryToReconnect();
			}
		});
	}

	private static void tryToConnect() throws Exception {
		LOGGER.info("Connecting...");
		// connect to the server using jnlp, user name and password
		if (isDemoMode) {
			speak("Demo Mode. Connecting");
			client.connect(jnlpUrl, userName, password);
		}
		else {
			speak("Warning! Live Mode. Connecting");
			client.connect(jnlpUrl, userName, password, PinDialog.showAndGetPin());
		}

		// wait for it to connect
		int i = 30;
		while (i > 0 && !client.isConnected()) {
			MyUtils.beep(1);
			Thread.sleep(500);
			i--;
		}
		if (!client.isConnected()) {
			LOGGER.error("Failed to connect Dukascopy servers");
			System.exit(1);
		}
	}

	private static void tryToReconnect() {
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				if (lightReconnects > 0) {
					client.reconnect();
					--lightReconnects;
				} else {
					do {
						try {
							Thread.sleep(60 * 1000);
						} catch (InterruptedException e) {
						}
						try {
							if (client.isConnected()) {
								break;
							}
							client.connect(jnlpUrl, userName, password);

						} catch (Exception e) {
							//LOGGER.error(e.getMessage(), e);
							LOGGER.error(e.getMessage());
						}
					} while (!client.isConnected());
				}
			}
		};
		new Thread(runnable).start();
	}


	private static void loadConfig() {
		File file = new File(baseDir, "my_config" + Main.separator + "config.properties");
		Main.prop = new Properties();
		InputStream is = null;
		try {
			is = new FileInputStream(file);
		} catch (FileNotFoundException ex) {
			LOGGER.error("Cannot found configuration file: " + file.getAbsolutePath());
			System.exit(0);
		}
		try {
			prop.load(is);
		} catch (IOException ex) {
			LOGGER.error("Cannot read configuration file: " + file.getAbsolutePath());
			System.exit(0);
		}
		if (prop.getProperty("demo", "yes").equalsIgnoreCase("no")) {
			isDemoMode = false;
			userName = prop.getProperty("d.live.username").trim();
			password = prop.getProperty("d.live.password").trim();
			jnlpUrl = "http://platform.dukascopy.com/live_3/jforex_3.jnlp";
		}
		else {
			isDemoMode = true;
			userName = prop.getProperty("d.demo.username").trim();
			password = prop.getProperty("d.demo.password").trim();
			jnlpUrl = "http://platform.dukascopy.com/demo/jforex.jnlp";
		}
	}
	
	public static boolean isStopping() {
		File file = new File(baseDir,  "my_config" + Main.separator + "continue.yes");
		return !file.exists();
	}
	public static IClient getClient() {
		return client;
	}
	
	@SuppressWarnings("serial")
	private static class PinDialog extends JDialog {
		
		private final JTextField pinfield = new JTextField();
		private final static JFrame noParentFrame = null;
		
		static String showAndGetPin() throws Exception{
			return new PinDialog().pinfield.getText();
		}

		public PinDialog() throws Exception {			
			super(noParentFrame, "PIN Dialog", true);
			
			JPanel captchaPanel = new JPanel();
			captchaPanel.setLayout(new BoxLayout(captchaPanel, BoxLayout.Y_AXIS));
			
			final JLabel captchaImage = new JLabel();
			captchaImage.setIcon(new ImageIcon(client.getCaptchaImage(jnlpUrl)));
			captchaPanel.add(captchaImage);
			
			
			captchaPanel.add(pinfield);
			getContentPane().add(captchaPanel);
			
			JPanel buttonPane = new JPanel();
			
			JButton btnLogin = new JButton("Login");
			buttonPane.add(btnLogin);
			btnLogin.addActionListener(new ActionListener() {

				@Override
				public void actionPerformed(ActionEvent e) {
					setVisible(false);
					dispose();
				}
			});
			
			JButton btnReload = new JButton("Reload");
			buttonPane.add(btnReload);
			btnReload.addActionListener(new ActionListener() {

				@Override
				public void actionPerformed(ActionEvent e) {
					try {
						captchaImage.setIcon(new ImageIcon(client.getCaptchaImage(jnlpUrl)));
					} catch (Exception ex) {
						LOGGER.info(ex.getMessage(), ex);
					}
				}
			});
			getContentPane().add(buttonPane, BorderLayout.SOUTH);
			setDefaultCloseOperation(DISPOSE_ON_CLOSE);
			pack();
			setVisible(true);
		}
	}
	private static String initSpeechEngine() {
		try {
			speechEngine = SpeechEngineNative.getInstance();
			
			List<Voice> voices = speechEngine.getAvailableVoices();

			System.out.println("For now the following voices are supported:\n");
			for (Voice voice : voices) {
				//System.out.printf("%s%n", voice);
			}
			Voice voice ;

			// We want to find a voice according to our preferences
			if (voiceId == -9999) {
				VoicePreferences voicePreferences = new VoicePreferences();
				voicePreferences.setLanguage("en"); //  ISO-639-1
				voicePreferences.setCountry("US"); // ISO 3166-1 Alpha-2 code
				voicePreferences.setGender(VoicePreferences.Gender.FEMALE);
				voice = speechEngine.findVoiceByPreferences(voicePreferences);
				
				// simple fallback just in case our preferences didn't match any voice
				if (voice == null) {
					voice = voices.get(0); // it is guaranteed that the speechEngine supports at least one voice
				}
				
				voiceId = voices.indexOf(voice);
				System.out.println(voiceId);
			}
			if (voiceId < 0)
				voiceId += voices.size();
			voiceId = voiceId % voices.size();
			voice = voices.get(voiceId);
			speechEngine.setVoice(voice.getName());
			speechEngine.setRate(0);
			return voice.getName();

		} catch (SpeechEngineCreationException e) {
			e.printStackTrace();
			return "";
		}
	}
	public static void selectNextVoice(int delta) {
		voiceId += delta;
		String voiceName = initSpeechEngine();
		speak(voiceName);
	}
	public static void speak(String text) {
		speak(text, 20);
	}
	public static void speak(String text, int rate) {
		if (speechEngine == null)
			initSpeechEngine();
		speechEngine.stopTalking();
		speechEngine.setRate(rate);
		try {
			speechEngine.say(text);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}