package rs117.hd.dev.editor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JPanel;

/** Compact summary and draft editor for a list of IDs. */
public final class SearchableIdDropdown extends JPanel {

	public static final class Choice {
		public final int id;
		public final String name;
		private final String searchText;
		public final String badgeLetter; // e.g. "N", "O", "G" — null/empty means no badge drawn for this row
		public final Color badgeColor; // ignored if badgeLetter is null/empty

		public Choice(int id, String name, String badgeLetter, Color badgeColor) {
			this(id, name, badgeLetter, badgeColor, name + " " + id);
		}

		public Choice(int id, String name, String badgeLetter, Color badgeColor, String searchText) {
			this.id = id;
			this.name = name;
			this.searchText = searchText.toLowerCase(Locale.ROOT);
			this.badgeLetter = badgeLetter;
			this.badgeColor = badgeColor;
		}
	}

	public interface Provider {
		List<Choice> items(); // full universe this dropdown can pick from
	}

	private final Provider provider;
	private final Set<Integer> selectedIds = new LinkedHashSet<>();
	private final List<Consumer<Set<Integer>>> listeners = new ArrayList<>();
	private final JPanel summary = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));

	private String searchPlaceholder = "Search by name or ID";
	private String title = "IDs";
	private java.util.function.IntFunction<String> nearby = id -> null;

	public static Builder builder(Provider provider) { return new Builder(provider); }

	public static final class Builder {
		private final Provider provider;
		private String placeholder = "Search by name or ID";
		private Set<Integer> selected;
		private String title = "IDs";
		private java.util.function.IntFunction<String> nearby = id -> null;
		private Consumer<Set<Integer>> listener;

		private Builder(Provider provider) { this.provider = provider; }
		public Builder searchPlaceholder(String value) { placeholder = value; return this; }
		public Builder selected(Set<Integer> ids) { selected = new LinkedHashSet<>(ids); return this; }
		public Builder onChange(Consumer<Set<Integer>> action) { listener = action; return this; }

		public Builder title(String value) { title = value; return this; }
		public Builder nearby(java.util.function.IntFunction<String> value) { nearby = value; return this; }

		public SearchableIdDropdown build() {
			var dropdown = new SearchableIdDropdown(provider);
			dropdown.title = title;
			dropdown.nearby = nearby;
			dropdown.setSearchPlaceholder(placeholder);
			if (selected != null)
				dropdown.setSelected(selected);
			if (listener != null)
				dropdown.onChange(listener);
			return dropdown;
		}
	}

	public SearchableIdDropdown(Provider provider) {
		this.provider = Objects.requireNonNull(provider);

		setOpaque(false);
		setLayout(new BorderLayout(0, 6));

		summary.setOpaque(false);
		add(summary, BorderLayout.CENTER);

		refreshSummary();
	}

	public void setSearchPlaceholder(String placeholder) {
		this.searchPlaceholder = placeholder;
	}

	public void setSelected(Set<Integer> ids) {
		selectedIds.clear();
		selectedIds.addAll(ids);
		refreshSummary();
		notifyChange();
	}

	public Set<Integer> getSelected() {
		return new LinkedHashSet<>(selectedIds);
	}

	public void onChange(Consumer<Set<Integer>> listener) {
		listeners.add(Objects.requireNonNull(listener));
	}

	private void openModal(boolean selectedOnly) {
		Map<Integer, Choice> itemsById = new LinkedHashMap<>();
		for (Choice choice : provider.items())
			itemsById.put(choice.id, choice);
		var options = new LinkedHashSet<>(selectedOnly ? selectedIds : itemsById.keySet());
		options.addAll(selectedIds);
		new SelectionModal<Integer>((selectedOnly ? "View " : "Add ") + title, new ArrayList<>(options), selectedIds, id -> {
			Choice choice = itemsById.get(id);
			return choice == null ? "ID " + id : choice.name;
		}).search(searchPlaceholder, id -> itemsById.containsKey(id) ? itemsById.get(id).searchText : Integer.toString(id))
			.badge(title.startsWith("NPC") ? "N" : title.startsWith("Object") ? "O" : "G",
				new Color(title.startsWith("NPC") ? 0xC25B5B : title.startsWith("Object") ? 0x5B8AC2 : 0x9A6BC2))
			.rowMeta(id -> Integer.toString(id))
			.nearby(nearby::apply)
			.rawOption(query -> {
				Integer id = parseInt(query);
				return !selectedOnly && id != null && id >= 0 && !options.contains(id) ? id : null;
			}).open(this, this::setSelected);
	}

	private void refreshSummary() {
		summary.removeAll();
		int count = selectedIds.size();
		if (count > 0) {
			summary.add(EditorButton.builder().text("View - " + count).onClick(() -> openModal(true)).build());
			summary.add(javax.swing.Box.createHorizontalStrut(6));
		} else {
			summary.add(EditorText.builder("None").color(WorkspaceStyle.DIM).build());
			summary.add(javax.swing.Box.createHorizontalStrut(6));
		}
		summary.add(DashedAddButton.create("Add", () -> openModal(false)));
		summary.revalidate();
		summary.repaint();
	}

	private void notifyChange() {
		var copy = new LinkedHashSet<>(selectedIds);
		for (Consumer<Set<Integer>> listener : listeners)
			listener.accept(new LinkedHashSet<>(copy));
	}

	private static Integer parseInt(String text) {
		String trimmed = text.trim();
		if (trimmed.isEmpty())
			return null;
		try {
			return Integer.valueOf(trimmed);
		} catch (NumberFormatException e) {
			return null;
		}
	}

}
