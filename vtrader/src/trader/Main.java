package trader;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

import javax.imageio.ImageIO;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

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
		// first thing of all: PowerShell needs about three seconds to come up, and starting it here
		// means it is sitting in its read loop long before anything is ready to be spoken
		Speaker.start();
		Runtime.getRuntime().addShutdownHook(new Thread(Speaker::shutdown, "tts-shutdown"));

		VoiceMenu menu = new VoiceMenu();
		menu.start();

		String s = "test";

		System.out.println("Starting...");
		
		// load configurations
		loadConfig();
		
		// get the instance of the IClient interface
		client = ClientFactory.getDefaultInstance();

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
		System.out.println("Setting system listener...");
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
		
		System.out.println("System listener was set successfully.");
	}

	private static void tryToConnect() throws Exception {
		LOGGER.info("Connecting...");
		// connect to the server using jnlp, user name and password
		if (isDemoMode) {
			System.out.println("DEMO");
			speak("Demo Mode. Connecting");
			client.connect(jnlpUrl, userName, password);
		}
		else {
			System.out.println("Live");
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
		File file = new File(baseDir, "my_config" + Main.separator + "config.txt");
		Main.prop = new Properties();
		InputStream is = null;
		try {
			is = new FileInputStream(file);
		} catch (FileNotFoundException ex) {
			System.out.println("Cannot found configuration file: " + file.getAbsolutePath());
			System.out.println("Error: " + ex.getMessage());
			LOGGER.error("Cannot found configuration file: " + file.getAbsolutePath());
			System.exit(0);
		}
		try {
			prop.load(is);
		} catch (IOException ex) {
			System.out.println("Cannot read configuration file: " + file.getAbsolutePath());
			System.out.println("Error: " + ex.getMessage());
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
		System.out.println("Configuration was loaded successfully.");
	}
	
	public static boolean isStopping() {
		return false;
		//File file = new File(baseDir,  "my_config" + Main.separator + "continue.yes");
		//return !file.exists();
	}
	public static IClient getClient() {
		return client;
	}
	
	// modal, so like the JDialog it replaces it runs its own dispatch loop and only returns once the
	// user has closed it - callers can keep reading the pin field's text right after the call returns
	private static class PinDialog {

		private String pin = "";

		static String showAndGetPin() throws Exception {
			return new PinDialog().run();
		}

		private String run() throws Exception {
			// its own Display: this runs on the main thread, on demand, well before VoiceMenu's
			// Display starts pumping on its own dedicated thread, so the two never collide
			Display display = new Display();
			try {
				Shell shell = new Shell(display, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
				shell.setText("PIN Dialog");
				shell.setLayout(new GridLayout(1, false));

				Label captchaLabel = new Label(shell, SWT.NONE);
				Image captchaImage = toSwtImage(display, client.getCaptchaImage(jnlpUrl));
				captchaLabel.setImage(captchaImage);

				Text pinField = new Text(shell, SWT.BORDER);
				pinField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

				Composite buttonBar = new Composite(shell, SWT.NONE);
				buttonBar.setLayout(new RowLayout());
				buttonBar.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

				Button btnLogin = new Button(buttonBar, SWT.PUSH);
				btnLogin.setText("Login");
				btnLogin.addListener(SWT.Selection, e -> shell.close());

				Button btnReload = new Button(buttonBar, SWT.PUSH);
				btnReload.setText("Reload");
				btnReload.addListener(SWT.Selection, e -> {
					try {
						Image fresh = toSwtImage(display, client.getCaptchaImage(jnlpUrl));
						captchaLabel.setImage(fresh);
						shell.layout(true, true);
						shell.pack();
						captchaImage.dispose(); // safe once nothing references the old image any more
					} catch (Exception ex) {
						LOGGER.info(ex.getMessage(), ex);
					}
				});

				shell.pack();
				shell.open();
				while (!shell.isDisposed()) {
					if (!display.readAndDispatch())
						display.sleep();
				}
				pin = pinField.getText();
			} finally {
				display.dispose();
			}
			return pin;
		}
	}

	// SWT has no constructor from a java.awt BufferedImage - the API only ever hands us one of
	// those, so it is re-encoded through ImageIO and read back as an SWT Image
	private static Image toSwtImage(Device device, BufferedImage bufferedImage) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", out);
		return new Image(device, new ByteArrayInputStream(out.toByteArray()));
	}
	private static String initSpeechEngine() {
		try {
			speechEngine = SpeechEngineNative.getInstance();
			
			List<Voice> voices = speechEngine.getAvailableVoices();
			Voice voice ;

			System.out.println("For now the following voices are supported:\n");
			for (Voice v : voices) {
				System.out.printf("%s%n", v);
				//if (v.getName().equalsIgnoreCase("Samantha") && v.getCulture().equalsIgnoreCase("en_US")) {
				//if (v.getName().equalsIgnoreCase("Microsoft Hazel Desktop") && v.getCulture().equalsIgnoreCase("en-GB")) {
					//voice = v;
					//voiceId = voices.indexOf(voice);
				//}
			}

			// default voice
			if (voiceId == -9999) {
				for (Voice v : voices) {
					if (v.getName().equalsIgnoreCase("Samantha") && v.getCulture().equalsIgnoreCase("en_US")) {
					// if (v.getName().equalsIgnoreCase("Microsoft Hazel Desktop") && v.getCulture().equalsIgnoreCase("en-GB")) {
						voice = v;
						voiceId = voices.indexOf(voice);
					}
				}
			}
			if (voiceId == -9999) {
			// We want to find a voice according to our preferences
				VoicePreferences voicePreferences = new VoicePreferences();
				voicePreferences.setLanguage("en"); //  ISO-639-1
				voicePreferences.setCountry("US"); // ISO 3166-1 Alpha-2 code
				voicePreferences.setGender(VoicePreferences.Gender.FEMALE);
				voice = speechEngine.findVoiceByPreferences(voicePreferences);
				
				// if no voice is matched, broaden criteria
				if (voice == null) {
					voicePreferences = new VoicePreferences();
					voicePreferences.setLanguage("en"); //  ISO-639-1
					voicePreferences.setGender(VoicePreferences.Gender.FEMALE);
					voice = speechEngine.findVoiceByPreferences(voicePreferences);
				}
				
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
			Speaker.setVoice(voice.getName()); // the live host picks the same voice
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
		// the long-lived host, when it is there: one line down a pipe instead of a three second
		// PowerShell launch. It cancels whatever is being said itself, so there is no stopTalking
		// here - that call exists to kill the per utterance process the library would have started.
		if (Speaker.say(text, rate))
			return;
		speechEngine.stopTalking();
		speechEngine.setRate(rate);
		try {
			speechEngine.say(text);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}