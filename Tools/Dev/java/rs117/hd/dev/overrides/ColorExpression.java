package rs117.hd.dev.overrides;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import rs117.hd.utils.ExpressionParser;

final class ColorExpression {
	private static final Pattern CONDITION = Pattern.compile("([hsl])\\s*(==|!=|<=|>=|<|>)\\s*(-?\\d+)");

	static final class Condition {
		final String channel, operator;
		final int value;

		Condition(String channel, String operator, int value) {
			this.channel = channel;
			this.operator = operator;
			this.value = value;
		}

		@Override public String toString() { return channel + " " + operator + " " + value; }
	}

	static List<List<Condition>> groups(String text) {
		var groups = new ArrayList<List<Condition>>();
		for (String group : text.split("\\|\\|", -1)) {
			var conditions = new ArrayList<Condition>();
			for (String term : group.split("&&", -1)) {
				var match = CONDITION.matcher(term.strip());
				if (!match.matches())
					return null;
				try {
					conditions.add(new Condition(match.group(1), match.group(2), Integer.parseInt(match.group(3))));
				} catch (NumberFormatException e) {
					return null;
				}
			}
			groups.add(conditions);
		}
		return groups;
	}

	static String format(List<List<Condition>> groups) {
		return groups.stream().map(group -> group.stream().map(Condition::toString)
			.collect(Collectors.joining(" && "))).collect(Collectors.joining(" || "));
	}

	static boolean valid(String text) {
		if (text.isBlank())
			return false;
		try {
			var expression = ExpressionParser.asExpression(ExpressionParser.parseExpression(text));
			for (var variable : expression.variables)
				if (!List.of("h", "s", "l", "a", "hsl", "ahsl").contains(variable))
					return false;
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	static String exact(int hsl) {
		return "h == " + (hsl >> 10 & 63) + " && s == " + (hsl >> 7 & 7) + " && l == " + (hsl & 127);
	}

	static String text(JsonElement value) {
		if (value == null || value.isJsonNull())
			return "";
		if (value.isJsonArray()) {
			var terms = new ArrayList<String>();
			for (var item : value.getAsJsonArray()) {
				String term = text(item);
				if (!term.isBlank())
					terms.add(groups(term) == null ? "(" + term + ")" : term);
			}
			return String.join(" || ", terms);
		}
		if (!value.isJsonPrimitive())
			return "false";
		if (value.getAsJsonPrimitive().isNumber()) {
			int packed = value.getAsInt();
			return packed >= 0 && packed <= 65535 ? exact(packed) : "false";
		}
		return value.getAsString();
	}
}
