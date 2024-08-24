package trader;
import java.io.ByteArrayInputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;

public class SineWaveGenerator{
	private Clip clip;

	private void generateTone() throws LineUnavailableException {
		if (clip != null) {
			clip.stop();
			clip.close();
		} else {
			clip = AudioSystem.getClip();
		}

		int sampleRate = 44100;
		int framesPerWavelength = 100; // Adjust this for the desired frequency
		int wavelengths = 200; // Number of wavelengths to generate
		float volume = 0.5f;
		byte[] buf = new byte[2 * framesPerWavelength * wavelengths];

		for (int i = 0; i < framesPerWavelength * wavelengths; i++) {
			double angle = (i * 2.0 / framesPerWavelength) * Math.PI;
			buf[i * 2] = (byte) (volume * Math.sin(angle) * 127);
			buf[i * 2 + 1] = buf[i * 2];
		}

		AudioFormat af = new AudioFormat(sampleRate, 8, 1, true, false);
		AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(buf), af, buf.length / 2);

		try {
			clip.open(ais);
			clip.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public  void play() {
		try {
			generateTone();
		} catch (LineUnavailableException e) {
			e.printStackTrace();
		}
	}
}
