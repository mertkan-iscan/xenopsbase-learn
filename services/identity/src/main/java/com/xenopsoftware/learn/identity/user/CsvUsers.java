package com.xenopsoftware.learn.identity.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The spreadsheet every customer arrives with, read (T-1.9).
 *
 * <p>Written rather than pulled in, because the format that matters here is small and the
 * failure mode of a dependency is not: a CSV library brings its own opinions about encodings,
 * headers and blank lines, and this needs exactly two columns and a line number to report errors
 * against. It handles what a spreadsheet export actually produces — quoted fields, commas and
 * newlines inside quotes, doubled quotes as an escape, CRLF, and a UTF-8 byte-order mark that
 * would otherwise make the first header column unrecognisable.
 *
 * <p>Line numbers are the file's, not the record's, so an error report points at the line the
 * customer can see in their editor.
 */
final class CsvUsers {

    /** One row as the file had it. {@code line} is 1-based and counts the header. */
    record Row(int line, String email, String displayName) {}

    private CsvUsers() {}

    static List<Row> parse(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<Integer> startLines = new ArrayList<>();
        readRecords(csv, records, startLines);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("The file is empty; expected a header row of "
                + "email,displayName");
        }

        List<String> header = records.get(0);
        int emailAt = columnOf(header, "email");
        int nameAt = columnOf(header, "displayname", "display_name", "name");
        if (emailAt < 0 || nameAt < 0) {
            throw new IllegalArgumentException("The header must name an email column and a "
                + "display name column; got " + header);
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.stream().allMatch(field -> field.isBlank())) {
                continue;
            }
            rows.add(new Row(startLines.get(i), at(record, emailAt), at(record, nameAt)));
        }
        return rows;
    }

    private static void readRecords(String csv, List<List<String>> records, List<Integer> startLines) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        int line = 1;
        int recordStart = 1;

        // A UTF-8 BOM is a character as far as the reader is concerned, and it lands in the
        // first header name -- "﻿email" matches nothing and the file looks headerless.
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!started) {
                recordStart = line;
                started = true;
            }
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    fields.add(field.toString().trim());
                    field.setLength(0);
                }
                case '\r' -> { }
                case '\n' -> {
                    fields.add(field.toString().trim());
                    field.setLength(0);
                    records.add(List.copyOf(fields));
                    startLines.add(recordStart);
                    fields.clear();
                    line++;
                    started = false;
                }
                default -> field.append(c);
            }
        }
        if (started || !field.isEmpty()) {
            fields.add(field.toString().trim());
            records.add(List.copyOf(fields));
            startLines.add(recordStart);
        }
    }

    private static int columnOf(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            String candidate = header.get(i).toLowerCase(Locale.ROOT).replace(" ", "");
            for (String name : names) {
                if (candidate.equals(name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String at(List<String> record, int index) {
        return index < record.size() ? record.get(index) : "";
    }
}
