package trader;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

// One PowerShell, started once and kept alive, instead of one PowerShell per utterance.
//
// The TTS library launches a fresh PowerShell for every single thing the program says. Measured on
// this machine: a bare PowerShell that does nothing but start and exit takes about 3.0 seconds, and
// all the speech work layered on top of it - loading System.Speech, building the synthesizer,
// picking the voice - accounts for only about 0.2 of that. So the delay before a word comes out is
// almost entirely the cost of starting a shell, and no amount of tuning the script inside it helps.
// cmd is fast to start, about 30 milliseconds, but it cannot speak; the hosts that can speak are the
// slow ones, and going through cscript and SAPI instead still costs about 1.9 seconds.
//
// So the shell is started once, at launch, and every utterance after that is one line written down
// a pipe to a process that is already sitting in a read loop with System.Speech loaded. The host
// reads one command per line: a letter, then base64 of the argument. Base64 rather than quoting,
// because the text being spoken contains apostrophes and percent signs and whatever else the
// program happens to be reporting, and none of it should ever have to survive a shell.
//
// This must never leave the program mute: every call falls back to the library's own one process
// per utterance path if the host cannot be started or has died.
class Speaker {

	private static final String HOST_SCRIPT = String.join("\n",
			"$ErrorActionPreference = 'SilentlyContinue'",
			"Add-Type -AssemblyName System.Speech",
			"$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
			"while ($true) {",
			"  $line = [Console]::In.ReadLine()",
			"  if ($null -eq $line) { break }",
			"  if ($line.Length -lt 1) { continue }",
			"  $c = $line.Substring(0, 1)",
			"  $a = $line.Substring(1)",
			"  try {",
			"    if ($c -eq 'S') {",
			"      $t = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($a))",
			// cancel first: every announcement in this program is meant to cut the previous one off
			"      $s.SpeakAsyncCancelAll()",
			// async, so a long sentence cannot block the loop and stop the next command being read
			"      [void]$s.SpeakAsync($t)",
			"    } elseif ($c -eq 'R') {",
			"      $s.Rate = [int]$a",
			"    } elseif ($c -eq 'V') {",
			"      $n = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($a))",
			"      $s.SelectVoice($n)",
			"    } elseif ($c -eq 'X') {",
			"      $s.SpeakAsyncCancelAll()",
			"    } elseif ($c -eq 'Q') {",
			"      break",
			"    }",
			"  } catch { }",
			"}",
			"$s.SpeakAsyncCancelAll()",
			"$s.Dispose()");

	// Windows only, and tested here rather than in start() so that the answer cannot depend on
	// whether start() was ever reached. On macOS the library runs the say command, and on Linux
	// spd-say: small native binaries that start in milliseconds, with no shell and no .NET assembly
	// behind them. Neither has the problem this class exists to solve, so on those two every method
	// below does nothing and reports failure, and the caller uses the library exactly as before.
	private static final boolean WINDOWS =
			System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

	private static Process process;
	private static Writer out;
	private static String voice; // re-sent to a host that had to be restarted
	private static int rate = Integer.MIN_VALUE;
	private static boolean unavailable; // the host could not be started; stop trying

	private Speaker() {
	}

	// Called once at launch. Returns as soon as the process exists - PowerShell then takes its three
	// seconds to reach the read loop on its own, while the program gets on with connecting. Anything
	// written in the meantime waits in the pipe and is read the moment the host is ready, so there is
	// nothing to wait for here.
	static synchronized void start() {
		if (WINDOWS)
			spawn();
	}

	static synchronized void setVoice(String name) {
		voice = name;
		if (name != null)
			send("V" + encode(name));
	}

	// text and the program's own 0 to 100 speech rate, the same number the library was being handed.
	// Returns false if it could not be said here, and the caller should fall back.
	static synchronized boolean say(String text, int appRate) {
		if (text == null)
			text = "";
		int wanted = powerShellRate(appRate);
		if (wanted != rate) {
			if (!send("R" + wanted))
				return false;
			rate = wanted;
		}
		return send("S" + encode(text));
	}

	static synchronized void stop() {
		send("X");
	}

	// System.Speech takes -10 to 10. The library mapped the program's rate this way and the voice it
	// produces is the one the user is used to, so the arithmetic is kept exactly as it was.
	private static int powerShellRate(int appRate) {
		int r = (int) Math.round(appRate / 10.0);
		return Math.max(-10, Math.min(10, r));
	}

	private static String encode(String s) {
		return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	// one line to the host, with one restart if the pipe is gone. A host that died takes the voice
	// and rate with it, so both are pushed again before the line that wanted them.
	private static boolean send(String line) {
		if (!WINDOWS || unavailable)
			return false;
		if (writeLine(line))
			return true;
		close();
		if (!spawn())
			return false;
		rate = Integer.MIN_VALUE;
		if (voice != null)
			writeLine("V" + encode(voice));
		return writeLine(line);
	}

	private static boolean writeLine(String line) {
		if (process == null || !process.isAlive() || out == null)
			return false;
		try {
			out.write(line);
			out.write('\n');
			out.flush();
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean spawn() {
		try {
			// -EncodedCommand takes UTF-16LE base64, which keeps the whole script off the command
			// line as far as quoting is concerned
			String encoded = Base64.getEncoder()
					.encodeToString(HOST_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
			ProcessBuilder builder = new ProcessBuilder("powershell.exe",
					"-NoProfile", "-NoLogo", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
			// nothing here reads the host's output, and a pipe nobody drains fills up and blocks the
			// host forever - which would look like the speech simply stopping, hours in
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			process = builder.start();
			out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			unavailable = true; // no PowerShell to be had; the library path is all there is
			process = null;
			out = null;
			return false;
		}
	}

	private static void close() {
		try {
			if (out != null)
				out.close();
		} catch (IOException ignored) {
		}
		if (process != null)
			process.destroyForcibly();
		out = null;
		process = null;
	}

	// the program exits through System.exit, so without this every run would leave its host behind
	static void shutdown() {
		synchronized (Speaker.class) {
			if (process == null)
				return;
			writeLine("Q");
			process.destroyForcibly();
			process = null;
			out = null;
		}
	}
}
