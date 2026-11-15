package dev.sift.engine.card;

import java.util.Locale;
import java.util.Set;

/**
 * The first one or two sentences of an article, for the definition card.
 *
 * <p>Only the first paragraph is used (text up to the first line break), so a heading like "History" can
 * never be part of it. A sentence ends at {@code .}, {@code !} or {@code ?} followed by a space and then a
 * capital letter, a digit or an opening quote/bracket, except after:
 * <ul>
 *   <li>a single letter ("J. R. R. Tolkien": initials),</li>
 *   <li>a word with a dot inside ("U.S.", "Ph.D.", "e.g."),</li>
 *   <li>a known short form ("Dr.", "St.", "Jr.", "Mr.", "c.", "approx.").</li>
 * </ul>
 * Worked example: "Tokyo is the capital and largest city of Japan. It is on the island of Honshu in the region
 * of Kanto. Tokyo is..." → sentence 1 has 9 words, fewer than {@code minWords} = 12, so sentence 2 is added:
 * "Tokyo is the capital and largest city of Japan. It is on the island of Honshu in the region of Kanto."
 *
 * <p>Some articles lost their first word when the wiki markup was removed ("is a type of Japanese poetry" for
 * Haiku). If the paragraph starts with a lowercase letter, the title is put in front: "Haiku is a type of ...".
 */
public final class OpeningSentences {

    private static final Set<String> SHORT_FORMS = Set.of("dr", "st", "jr", "sr", "mr", "mrs", "ms", "prof",
            "mt", "ft", "vs", "no", "c", "ca", "approx", "est", "lit", "gen", "col", "lt", "sgt", "rev", "inc",
            "ltd", "co", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec");

    private OpeningSentences() {
    }

    /**
     * @param minWords add the next sentence while the text has fewer words than this (at most 2 sentences)
     * @param maxWords cut at this many words, at a word boundary, ending with "…"
     */
    public static String of(String title, String text, int minWords, int maxWords) {
        String paragraph = firstParagraph(text);
        if (paragraph.isEmpty()) {
            return "";
        }
        if (Character.isLowerCase(paragraph.codePointAt(0))) {
            paragraph = title + " " + paragraph;
        }
        int end = sentenceEnd(paragraph, 0);
        if (wordCount(paragraph.substring(0, end)) < minWords && end < paragraph.length()) {
            end = sentenceEnd(paragraph, end);
        }
        return cap(paragraph.substring(0, end).strip(), maxWords);
    }

    private static String firstParagraph(String text) {
        String t = text.strip();
        int nl = t.indexOf('\n');
        return (nl < 0 ? t : t.substring(0, nl)).strip();
    }

    /** Index just after the end of the sentence that starts at {@code from} (or the paragraph's end). */
    static int sentenceEnd(String p, int from) {
        for (int i = from; i < p.length(); i++) {
            char c = p.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && endsSentence(p, i)) {
                return i + 1;
            }
        }
        return p.length();
    }

    private static boolean endsSentence(String p, int dot) {
        int next = dot + 1;
        // closing quotes or brackets may follow the mark: '... called "Liber Abaci".'
        while (next < p.length() && (p.charAt(next) == '"' || p.charAt(next) == ')' || p.charAt(next) == '\'')) {
            next++;
        }
        if (next >= p.length()) {
            return true;
        }
        if (p.charAt(next) != ' ') {
            return false;   // "3.14", "U.S.A", "example.com"
        }
        int start = next;
        while (start < p.length() && p.charAt(start) == ' ') {
            start++;
        }
        if (start >= p.length()) {
            return true;
        }
        char first = p.charAt(start);
        if (!(Character.isUpperCase(first) || Character.isDigit(first) || first == '"' || first == '(')) {
            return false;   // "... born c. 1500 in ..." continues in lowercase
        }
        if (p.charAt(dot) != '.') {
            return true;    // ! and ? always end a sentence when a capital follows
        }
        String word = wordBefore(p, dot);
        if (word.length() == 1 && Character.isLetter(word.charAt(0))) {
            return false;   // an initial: "J. R. R. Tolkien"
        }
        if (word.indexOf('.') >= 0) {
            return false;   // "U.S." "Ph.D." "e.g."
        }
        return !SHORT_FORMS.contains(word.toLowerCase(Locale.ROOT));
    }

    /** The characters before {@code dot} back to the previous space or bracket: "Ph.D" for "a Ph.D.". */
    private static String wordBefore(String p, int dot) {
        int s = dot;
        while (s > 0 && p.charAt(s - 1) != ' ' && p.charAt(s - 1) != '(' && p.charAt(s - 1) != '"') {
            s--;
        }
        return p.substring(s, dot);
    }

    static int wordCount(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static String cap(String s, int maxWords) {
        String[] words = s.split("\\s+");
        if (words.length <= maxWords) {
            return s;
        }
        return String.join(" ", java.util.Arrays.copyOf(words, maxWords)).replaceAll("[,;:(–-]+$", "") + " …";
    }
}
