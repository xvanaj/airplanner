package org.example.airplanner.output;

import java.util.ArrayList;
import java.util.List;

/** A plain, width-aligned text table. ASCII only, so it survives any console code page. */
public class Table {

    public enum Align { LEFT, RIGHT }

    private final List<String> headers = new ArrayList<>();
    private final List<Align> aligns = new ArrayList<>();
    private final List<List<String>> rows = new ArrayList<>();

    public Table column(String header) {
        return column(header, Align.LEFT);
    }

    public Table column(String header, Align align) {
        headers.add(header);
        aligns.add(align);
        return this;
    }

    public Table row(Object... cells) {
        List<String> row = new ArrayList<>(headers.size());
        for (Object cell : cells) {
            row.add(cell == null ? "" : cell.toString());
        }
        while (row.size() < headers.size()) {
            row.add("");
        }
        rows.add(row);
        return this;
    }

    public int size() {
        return rows.size();
    }

    public String render() {
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            widths[i] = headers.get(i).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < headers.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers, widths);
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            rule.append("-".repeat(widths[i]));
            if (i < widths.length - 1) {
                rule.append("  ");
            }
        }
        sb.append(rule).append(System.lineSeparator());
        for (List<String> row : rows) {
            appendRow(sb, row, widths);
        }
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, List<String> cells, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            String cell = cells.get(i);
            String padded = aligns.get(i) == Align.RIGHT
                    ? " ".repeat(widths[i] - cell.length()) + cell
                    : cell + " ".repeat(widths[i] - cell.length());
            sb.append(i == widths.length - 1 ? padded.stripTrailing() : padded);
            if (i < widths.length - 1) {
                sb.append("  ");
            }
        }
        sb.append(System.lineSeparator());
    }
}
