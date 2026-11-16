# 117HD Editor design rules

These rules apply to the editor in `Tools/Dev`.

## Typography

- Use IBM Plex Sans for labels, buttons, menus, headings, help text, and body copy. Use `WorkspaceStyle.UI` for 400, `MEDIUM` for 500, and `SEMIBOLD` for 600.
- Use JetBrains Mono for values, IDs, gamevals, enum names, coordinates, shortcuts, and expressions such as `h == 63 && s > 0`. Use `WorkspaceStyle.MONO` for 400 and `MONO_SEMIBOLD` for 600.
- Use the bundled font faces through `WorkspaceStyle` and derive only the size. Give mixed labels and technical values separate components when practical. Avoid Swing's default fonts and synthetic bold or italic.
- Segmented controls use UI typography by default. Use `.values()` for segments that show values or enum names.

## Reusable UI

- Use the existing editor builders for buttons, text, selects, segmented controls, toggles, and modals. Start with `EditorButton`, `EditorText`, `EditorSelect`, `SegmentedControl`, `ToggleSwitch`, and `EditorModal`.
- Put reusable variants, icons, state styling, and interaction behavior in the shared editor components. Keep screen-specific data and action callbacks in the screen. Use `WorkspaceStyle` for palette tokens, SVG icons, and custom cursors.
- Use `PropertyRow` for aligned property fields, `PropertySection` for collapsible groups, `PopupLayer` for dropdowns, `HdContextMenu` for context menus, and `EditorScrollPane` where content needs scrolling.
- Check narrow docks and open menus against the handoff. Preserve control heights, align field edges, and keep labels and buttons clear of clipping. Verify hover, focus, selected, and disabled states when changing a shared component.
- Keep editor changes in this repository's developer tools. Use the existing reflection adapters when accessing RLHD internals.

### Adding editor controls

- Use `EditorButton.builder()` for actions and toggles. Omit a fixed size for text buttons so the label can fit.
- Use `EditorSearch.builder()` for search panels. Set the placeholder and `onChange` callback, and use `field()` to focus, clear, or read the search.
- Use `EditorButtonGroup.builder()` for content-width mode switches. Add `track()` for the viewport toolbar style. Use `SegmentedControl.builder()` for equal-width property options.
- Use `EditorPanels.stack()` for a transparent column and `EditorPanels.centered(component)` to preserve a control's preferred size inside a stretching panel.

Example for a filter and a viewport mode switch

```java
var filter = EditorSearch.builder("Filter by name")
    .onChange(this::filterByName)
    .build();

var modes = EditorButtonGroup.builder(List.of("Vanilla", "Override", "Split"))
    .selected("Override")
    .track()
    .onChange(this::setPreviewMode)
    .build();
```
