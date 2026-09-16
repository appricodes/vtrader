package trader;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Turns one announcement into the short list of audio clips that say it.
//
// Everything this program says is mostly digits, and digits are a closed set: read three at a time,
// every group is a number from 0 to 999. So a thousand recordings cover every price, profit and
// quantity the program will ever report, and they are made once and kept. What is left over - the
// words between the numbers - is a small fixed vocabulary that gets the same treatment.
//
// The grouping follows what a person does out loud: the integer part in threes from the right, the
// fractional part in threes from the left.
//
//   12345.98765  ->  12 | 345 | point | 987 | 65
//
// Leading zeros inside a group are spoken, because 12045 and 12450 must not sound alike, and the
// whole group is one recording: "045" is its own clip, not a zero clip butted against a forty five
// clip. Stitching sounded worse than it costs, so the set covers every group of one, two and three
// digits - a little over eleven hundred clips, made once.
//
// No I/O here, and no network: this is only the decision about what should be said. Fetching and
// playing the clips is somebody else's job.
class SpeechTokens {

	// a number, optionally signed, optionally fractional, optionally in the exponent form that
	// Double.toString falls into for very small values
	private static final Pattern NUMBER =
			Pattern.compile("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");
	// an order label: a letter or two, a number, and sometimes a trailing letter - B166, B166R
	private static final Pattern LABEL = Pattern.compile("\\b[A-Za-z]{1,2}\\d+[A-Za-z]?\\b");
	private static final Pattern LABEL_PARTS = Pattern.compile("([A-Za-z]{1,2})(\\d+)([A-Za-z]?)");
	private static final Pattern DIGIT = Pattern.compile("\\d");
	// a run with nothing in it but punctuation and spaces is not worth a clip. The percent sign is
	// the exception: it carries meaning, and one that nobody remembered to map must still be spoken
	// rather than quietly lost - five and five percent are not the same number.
	private static final Pattern HAS_CONTENT = Pattern.compile("[\\p{L}\\p{N}%]");

	static final String FA = "fa";
	static final String EN = "en";

	static final String POINT = "ممیز";
	static final String MINUS = "منفی";
	static final String PLUS = "مثبت";

	private static final String[] ONES =
		{ "صفر", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه" };
	private static final String[] TEENS =
		{ "ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده" };
	private static final String[] TENS =
		{ "", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود" };
	private static final String[] HUNDREDS =
		{ "", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد" };

	// One clip. The text is both what gets synthesized and what identifies it in the cache, so two
	// announcements that need the same words share a file.
	static final class Token {
		final String lang;
		final String text;

		Token(String lang, String text) {
			this.lang = lang;
			this.text = text;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Token))
				return false;
			Token t = (Token) o;
			return lang.equals(t.lang) && text.equals(t.text);
		}

		@Override
		public int hashCode() {
			return lang.hashCode() * 31 + text.hashCode();
		}

		@Override
		public String toString() {
			return lang + ":" + text;
		}
	}

	private SpeechTokens() {
	}

	private static Token fa(String text) {
		return new Token(FA, text);
	}

	// 0 to 999 in Persian words. Above 999 never comes up: the grouping hands this three digits.
	static String persianWords(int n) {
		if (n < 0 || n > 999)
			throw new IllegalArgumentException("out of range: " + n);
		if (n == 0)
			return ONES[0];
		List<String> parts = new ArrayList<>(3);
		int hundreds = n / 100;
		if (hundreds > 0)
			parts.add(HUNDREDS[hundreds]);
		int rest = n % 100;
		if (rest >= 10 && rest <= 19) {
			parts.add(TEENS[rest - 10]);
		}
		else {
			int tens = rest / 10;
			if (tens > 0)
				parts.add(TENS[tens]);
			int ones = rest % 10;
			if (ones > 0)
				parts.add(ONES[ones]);
		}
		return String.join(" و ", parts);
	}

	// One group of digits, said the way it appears. Leading zeros are spoken, because 12045 and
	// 12450 must not sound alike, and the whole group is one clip rather than several stitched
	// together - "صفر چهل و پنج" recorded in one breath beats a zero clip butted against a forty
	// five clip.
	static String groupWords(String digits) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < digits.length() - 1 && digits.charAt(i) == '0') {
			out.append(ONES[0]).append(' ');
			i++;
		}
		out.append(persianWords(Integer.parseInt(digits.substring(i))));
		return out.toString();
	}

	// Every clip the number vocabulary needs, made once and never again. Groups of one, two and
	// three digits are all here: three digits for the body of a number, and the shorter ones for the
	// leading group and for a fraction that does not divide evenly by three.
	static List<Token> numberVocabulary() {
		LinkedHashSet<Token> all = new LinkedHashSet<>();
		for (int i = 0; i <= 9; i++)
			all.add(fa(groupWords(String.format("%01d", i))));
		for (int i = 0; i <= 99; i++)
			all.add(fa(groupWords(String.format("%02d", i))));
		for (int i = 0; i <= 999; i++)
			all.add(fa(groupWords(String.format("%03d", i))));
		all.add(fa(POINT));
		all.add(fa(MINUS));
		all.add(fa(PLUS));
		return new ArrayList<>(all);
	}

	// The whole announcement. Runs of digits become Persian number clips; everything between them is
	// looked up in the phrase map and spoken in Persian when it is there, and in English when it is
	// not - which is where anything unforeseen ends up.
	//
	// protect holds the names that must survive intact: instrument names carry digits of their own,
	// and US30 or USA500.IDX/USD read as Persian numbers would be gibberish. They are matched before
	// anything else, longest first, and go out as one English clip.
	static List<Token> tokenize(String text, Map<String, String> phrases, Collection<String> protect) {
		List<Token> out = new ArrayList<>();
		if (text == null)
			return out;
		Pattern pattern = patternFor(protect, phrases);
		Matcher m = pattern.matcher(text);
		int at = 0;
		while (m.find()) {
			addWords(out, text.substring(at, m.start()), phrases);
			String whole = m.group("prot");
			String label = m.group("label");
			if (whole != null) {
				// matched in one piece on purpose - never split on the punctuation inside it
				addPhrase(out, whole, phrases);
			}
			else if (label != null) {
				// an order label: B166, or B166R once a reverse position is hung off it. The letters
				// belong to the label, not to the sentence in front of it - without this, "reverse
				// guard set on B166" leaves "...set on B" as the fragment to look up, and nothing
				// matches it.
				Matcher parts = LABEL_PARTS.matcher(label);
				if (parts.matches()) {
					addPhrase(out, parts.group(1), phrases);
					addNumber(out, parts.group(2));
					if (!parts.group(3).isEmpty())
						addPhrase(out, parts.group(3), phrases);
				}
			}
			else {
				addNumber(out, m.group("num"));
			}
			at = m.end();
		}
		addWords(out, text.substring(at), phrases);
		return out;
	}

	static List<Token> tokenize(String text, Map<String, String> phrases) {
		return tokenize(text, phrases, null);
	}

	private static Pattern patternFor(Collection<String> protect, Map<String, String> phrases) {
		List<String> terms = new ArrayList<>();
		if (protect != null)
			for (String t : protect)
				if (t != null && !t.trim().isEmpty())
					terms.add(t.trim());
		// A phrase with a digit in it has to be matched whole, or the digit is torn out as a number
		// and the rest no longer matches anything: "by pressing F2" would be looked up as
		// "by pressing f" and fall through to English.
		if (phrases != null)
			for (String key : phrases.keySet())
				if (key != null && DIGIT.matcher(key).find())
					terms.add(key.trim());
		// longest first, so USA500.IDX/USD is taken whole rather than being cut short by USA500
		terms.sort((a, b) -> b.length() - a.length());
		StringBuilder alternation = new StringBuilder();
		for (String t : terms) {
			if (alternation.length() > 0)
				alternation.append('|');
			alternation.append(Pattern.quote(t));
		}
		String prot = (alternation.length() == 0) ? "(?!x)x" : alternation.toString();
		return Pattern.compile(
				"(?<prot>" + prot + ")"
				+ "|(?<label>" + LABEL.pattern() + ")"
				+ "|(?<num>" + NUMBER.pattern() + ")",
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	// the whole fragment, looked up as it stands. Unlike addWords this never splits on punctuation,
	// because whatever got here was matched deliberately and in one piece.
	private static void addPhrase(List<Token> out, String text, Map<String, String> phrases) {
		String cleaned = text.trim().replaceAll("\\s+", " ");
		if (cleaned.isEmpty())
			return;
		String persian = (phrases == null) ? null : phrases.get(cleaned.toLowerCase(Locale.ROOT));
		if (persian != null) {
			if (!persian.isEmpty())
				out.add(fa(persian));
			return;
		}
		if (HAS_CONTENT.matcher(cleaned).find())
			out.add(new Token(EN, cleaned));
	}

	// A run of words between two numbers is not one phrase: "x, EUR/USD, open price:" is three
	// things that happen to have no digits between them. Splitting on the punctuation the format
	// strings already use gives the phrase map fragments it has a chance of matching.
	private static void addWords(List<Token> out, String run, Map<String, String> phrases) {
		for (String piece : run.split("[,;.!?]")) {
			String cleaned = piece.trim().replaceAll("\\s+", " ");
			if (cleaned.isEmpty())
				continue;
			// the map is keyed on the fragment as it appears in the code, give or take spacing and case
			String key = cleaned.toLowerCase(Locale.ROOT);
			String persian = (phrases == null) ? null : phrases.get(key);
			if (persian != null && !persian.isEmpty()) {
				out.add(fa(persian));
				continue;
			}
			// The map is consulted before this test on purpose. A run of pure punctuation is usually
			// a leftover colon and not worth a clip, but "%" is not leftover - dropping it turns five
			// percent into five, and this program talks about percentages constantly.
			if (!HAS_CONTENT.matcher(cleaned).find())
				continue;
			out.add(new Token(EN, cleaned));
		}
	}

	private static void addNumber(List<Token> out, String number) {
		String s = number;
		// 1.0E-5 would otherwise be read as one number, a letter and another number
		if (s.indexOf('e') >= 0 || s.indexOf('E') >= 0)
			s = new BigDecimal(s).toPlainString();
		if (s.startsWith("-")) {
			out.add(fa(MINUS));
			s = s.substring(1);
		}
		else if (s.startsWith("+")) {
			out.add(fa(PLUS));
			s = s.substring(1);
		}
		String whole = s;
		String fraction = "";
		int dot = s.indexOf('.');
		if (dot >= 0) {
			whole = s.substring(0, dot);
			fraction = s.substring(dot + 1);
		}
		if (whole.isEmpty())
			whole = "0";

		// integer part: threes from the right, so the first group is whatever is left over
		int first = whole.length() % 3;
		if (first == 0)
			first = 3;
		addGroup(out, whole.substring(0, first), false);
		for (int i = first; i < whole.length(); i += 3)
			addGroup(out, whole.substring(i, i + 3), true);

		if (fraction.isEmpty())
			return;
		out.add(fa(POINT));
		// fractional part: threes from the left, and the last group can be short
		for (int i = 0; i < fraction.length(); i += 3)
			addGroup(out, fraction.substring(i, Math.min(i + 3, fraction.length())), true);
	}

	// One group of at most three digits, as a single clip. The very first group of a number has no
	// leading zeros to speak, being the top of it, so it is stripped down to its plain value and
	// shares the clip a bare number of that size would use.
	private static void addGroup(List<Token> out, String digits, boolean speakLeadingZeros) {
		String group = digits;
		if (!speakLeadingZeros) {
			int i = 0;
			while (i < group.length() - 1 && group.charAt(i) == '0')
				i++;
			group = group.substring(i);
		}
		out.add(fa(groupWords(group)));
	}
}
