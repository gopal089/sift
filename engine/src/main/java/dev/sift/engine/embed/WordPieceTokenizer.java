package dev.sift.engine.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The BERT "uncased" WordPiece tokenizer, written by hand. It turns text into the word-piece ids the
 * embedding model was trained on, and must give <b>exactly</b> the same ids as the model's own tokenizer
 * (checked by a test on 1,000 strings).
 *
 * <p>Three stages:
 * <ol>
 *   <li><b>Normalise:</b> drop control characters, turn every kind of space into ' ', put spaces around
 *       Chinese/Japanese characters, remove accents (é → e), lowercase.</li>
 *   <li><b>Pre-tokenize:</b> split on spaces, and make every punctuation mark its own word.</li>
 *   <li><b>WordPiece:</b> cut each word into the longest pieces found in the vocabulary, left to right.
 *       Pieces after the first start with "##": "unaffable" → un, ##aff, ##able.
 *       A word that cannot be cut (or is over 100 characters) becomes [UNK].</li>
 * </ol>
 * Finally [CLS] goes in front and [SEP] at the end, and the list is cut to {@code maxLength} ids.
 */
public final class WordPieceTokenizer {

    public static final String CLS = "[CLS]";
    public static final String SEP = "[SEP]";
    public static final String UNK = "[UNK]";
    static final String CONTINUATION = "##";
    static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> ids;
    private final String[] pieces;
    private final int clsId;
    private final int sepId;
    private final int unkId;

    /** @param vocab the word pieces; the position in the list is the id */
    public WordPieceTokenizer(List<String> vocab) {
        this.pieces = vocab.toArray(new String[0]);
        this.ids = new HashMap<>(vocab.size() * 2);
        for (int i = 0; i < pieces.length; i++) {
            ids.putIfAbsent(pieces[i], i);
        }
        this.clsId = require(CLS);
        this.sepId = require(SEP);
        this.unkId = require(UNK);
    }

    /** Reads {@code vocab.txt}: one word piece per line, line number (from 0) = id. */
    public static WordPieceTokenizer load(Path vocabFile) throws IOException {
        return new WordPieceTokenizer(Files.readAllLines(vocabFile, StandardCharsets.UTF_8));
    }

    private int require(String token) {
        Integer id = ids.get(token);
        if (id == null) {
            throw new IllegalArgumentException("vocabulary has no " + token);
        }
        return id;
    }

    public int vocabSize() {
        return pieces.length;
    }

    public String piece(int id) {
        return pieces[id];
    }

    /** [CLS] + word pieces + [SEP], at most {@code maxLength} ids (word pieces are cut, [SEP] is kept). */
    public int[] encode(String text, int maxLength) {
        if (maxLength < 2) {
            throw new IllegalArgumentException("maxLength must be at least 2");
        }
        List<Integer> out = new ArrayList<>();
        out.add(clsId);
        for (String word : basicTokens(text)) {
            if (out.size() >= maxLength - 1) {
                break;
            }
            wordPieces(word, out);
        }
        int n = Math.min(out.size(), maxLength - 1);
        int[] result = new int[n + 1];
        for (int i = 0; i < n; i++) {
            result[i] = out.get(i);
        }
        result[n] = sepId;
        return result;
    }

    // ------------------------------------------------------------------ stage 1 + 2: normalise and split

    /** The words after normalising and splitting, before WordPiece. "Café, déjà-vu!" → [cafe, ",", deja, "-", vu, "!"]. */
    public static List<String> basicTokens(String text) {
        String normalised = normalise(text);
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < normalised.length(); ) {
            int cp = normalised.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                flush(word, words);
            } else if (isPunctuation(cp)) {
                flush(word, words);
                words.add(new String(Character.toChars(cp)));
            } else {
                word.appendCodePoint(cp);
            }
        }
        flush(word, words);
        return words;
    }

    private static void flush(StringBuilder word, List<String> words) {
        if (!word.isEmpty()) {
            words.add(word.toString());
            word.setLength(0);
        }
    }

    /** Clean, space out CJK, strip accents, lowercase — in that order (the order matters for e.g. 'İ'). */
    static String normalise(String text) {
        StringBuilder cleaned = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) {
                continue;                                    // dropped
            }
            if (isWhitespace(cp)) {
                cleaned.append(' ');
            } else if (isChineseChar(cp)) {
                cleaned.append(' ').appendCodePoint(cp).append(' ');
            } else {
                cleaned.appendCodePoint(cp);
            }
        }
        // NFD splits "é" into "e" + a combining accent mark; then the marks (category Mn) are dropped
        String decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) != Character.NON_SPACING_MARK) {
                out.appendCodePoint(Character.toLowerCase(cp));   // one character at a time, like the model's tokenizer
            }
        }
        return out.toString();
    }

    /** Tab, newline and carriage return count as spaces, not as control characters. */
    static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') {
            return false;
        }
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                || type == Character.PRIVATE_USE || type == Character.UNASSIGNED;
    }

    /** Unicode "White_Space" characters (Java's own isWhitespace leaves out no-break spaces, so we list them). */
    static boolean isWhitespace(int cp) {
        return switch (cp) {
            case '\t', '\n', 0x0B, 0x0C, '\r', ' ', 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true;
            default -> cp >= 0x2000 && cp <= 0x200A;
        };
    }

    /** ASCII symbols like $ + < = > ^ ` | ~ count as punctuation too, plus every Unicode punctuation class. */
    static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    /** The "CJK Unified Ideographs" blocks (Chinese characters, also used in Japanese and Korean). */
    static boolean isChineseChar(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0x2A700 && cp <= 0x2B73F)
                || (cp >= 0x2B740 && cp <= 0x2B81F) || (cp >= 0x2B920 && cp <= 0x2CEAF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    // ------------------------------------------------------------------ stage 3: WordPiece

    /** Greedy longest match first. Adds the pieces' ids to {@code out}; the whole word is [UNK] if any part fails. */
    void wordPieces(String word, List<Integer> out) {
        int[] cps = word.codePoints().toArray();
        if (cps.length > MAX_CHARS_PER_WORD) {
            out.add(unkId);
            return;
        }
        List<Integer> found = new ArrayList<>();
        int start = 0;
        while (start < cps.length) {
            int end = cps.length;
            Integer id = null;
            while (start < end) {                               // try the longest piece first, then shorter
                String sub = new String(cps, start, end - start);
                id = ids.get(start > 0 ? CONTINUATION + sub : sub);
                if (id != null) {
                    break;
                }
                end--;
            }
            if (id == null) {
                out.add(unkId);
                return;
            }
            found.add(id);
            start = end;
        }
        out.addAll(found);
    }
}
